package org.sedo.satmesh;


import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import net.zetetic.database.sqlcipher.SupportOpenHelperFactory;

import org.sedo.satmesh.model.Message;
import org.sedo.satmesh.model.MessageDao;
import org.sedo.satmesh.model.MessageFts;
import org.sedo.satmesh.model.Node;
import org.sedo.satmesh.model.NodeDao;
import org.sedo.satmesh.model.SignalKeyExchangeState;
import org.sedo.satmesh.model.rt.BroadcastStatusEntry;
import org.sedo.satmesh.model.rt.BroadcastStatusEntryDao;
import org.sedo.satmesh.model.rt.RouteEntry;
import org.sedo.satmesh.model.rt.RouteEntryDao;
import org.sedo.satmesh.model.rt.RouteRequestEntry;
import org.sedo.satmesh.model.rt.RouteRequestEntryDao;
import org.sedo.satmesh.model.rt.RouteUsage;
import org.sedo.satmesh.model.rt.RouteUsageDao;
import org.sedo.satmesh.signal.model.SignalIdentityKeyDao;
import org.sedo.satmesh.signal.model.SignalIdentityKeyEntity;
import org.sedo.satmesh.signal.model.SignalPreKeyDao;
import org.sedo.satmesh.signal.model.SignalPreKeyEntity;
import org.sedo.satmesh.signal.model.SignalSessionDao;
import org.sedo.satmesh.signal.model.SignalSessionEntity;
import org.sedo.satmesh.signal.model.SignalSignedPreKeyDao;
import org.sedo.satmesh.signal.model.SignalSignedPreKeyEntity;
import org.sedo.satmesh.utils.AndroidKeyManager;

@Database(entities = {Node.class, Message.class, MessageFts.class,
		SignalSessionEntity.class, SignalPreKeyEntity.class,
		SignalSignedPreKeyEntity.class, SignalIdentityKeyEntity.class,
		SignalKeyExchangeState.class, RouteEntry.class, RouteRequestEntry.class,
		RouteUsage.class, BroadcastStatusEntry.class},
		version = 5)
public abstract class AppDatabase extends RoomDatabase {

	// Define migrations here
	static final Migration MIGRATION_1_2 = new Migration(1, 2) {
		@Override
		public void migrate(@NonNull SupportSQLiteDatabase database) {
			database.execSQL("CREATE TABLE IF NOT EXISTS `route_entry` (" +
					"`id` INTEGER PRIMARY KEY AUTOINCREMENT, " +
					"`discovery_uuid` TEXT, " +
					"`destination_node_local_id` INTEGER, " +
					"`next_hop_local_id` INTEGER, " +
					"`previous_hop_local_id` INTEGER, " +
					"`hop_count` INTEGER)");

			database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_route_entry_discovery_uuid` ON `route_entry` (`discovery_uuid`)");
			database.execSQL("CREATE INDEX IF NOT EXISTS `index_route_entry_destination_node_local_id` ON `route_entry` (`destination_node_local_id`)");
			database.execSQL("CREATE INDEX IF NOT EXISTS `index_route_entry_next_hop_local_id` ON `route_entry` (`next_hop_local_id`)");
			database.execSQL("CREATE INDEX IF NOT EXISTS `index_route_entry_previous_hop_local_id` ON `route_entry` (`previous_hop_local_id`)");

			// Création de la table route_request_entry
			database.execSQL("CREATE TABLE IF NOT EXISTS `route_request_entry` (" +
					"`request_uuid` TEXT NOT NULL, " +
					"`destination_node_local_id` INTEGER, " +
					"`previous_hop_local_id` INTEGER, " +
					"PRIMARY KEY(`request_uuid`))");

			// Création de la table route_usage
			database.execSQL("CREATE TABLE IF NOT EXISTS `route_usage` (" +
					"`usage_request_uuid` TEXT NOT NULL, " +
					"`route_entry_discovery_uuid` TEXT, " +
					"`last_used_timestamp` INTEGER, " +
					"PRIMARY KEY(`usage_request_uuid`), " +
					"FOREIGN KEY(`route_entry_discovery_uuid`) REFERENCES `route_entry`(`discovery_uuid`) ON UPDATE NO ACTION ON DELETE CASCADE)");

			database.execSQL("CREATE INDEX IF NOT EXISTS `index_route_usage_route_entry_discovery_uuid` ON `route_usage` (`route_entry_discovery_uuid`)");

			// Création de la table broadcast_status_entry
			database.execSQL("CREATE TABLE IF NOT EXISTS `broadcast_status_entry` (" +
					"`request_uuid` TEXT NOT NULL, " +
					"`neighbor_node_local_id` INTEGER NOT NULL, " +
					"`is_pending_response_in_progress` INTEGER NOT NULL, " +
					"PRIMARY KEY(`request_uuid`, `neighbor_node_local_id`), " +
					"FOREIGN KEY(`request_uuid`) REFERENCES `route_request_entry`(`request_uuid`) ON UPDATE NO ACTION ON DELETE CASCADE)");

			database.execSQL("CREATE INDEX IF NOT EXISTS `index_broadcast_status_entry_request_uuid` ON `broadcast_status_entry` (`request_uuid`)");
			database.execSQL("CREATE INDEX IF NOT EXISTS `index_broadcast_status_entry_neighbor_node_local_id` ON `broadcast_status_entry` (`neighbor_node_local_id`)");
		}
	};

	static final Migration MIGRATION_2_3 = new Migration(2, 3) {
		@Override
		public void migrate(@NonNull SupportSQLiteDatabase database) {
			// No structural change
		}
	};

	static final Migration MIGRATION_3_4 = new Migration(3, 4) {
		@Override
		public void migrate(@NonNull SupportSQLiteDatabase database) {
			database.execSQL("PRAGMA foreign_keys=OFF");

			// 1. DROP INDICES
			database.execSQL("DROP INDEX IF EXISTS index_node_addressName");
			database.execSQL("DROP INDEX IF EXISTS index_message_payloadId");
			database.execSQL("DROP INDEX IF EXISTS index_message_senderNodeId");
			database.execSQL("DROP INDEX IF EXISTS index_message_recipientNodeId");

			// 2. NODE MIGRATION
			database.execSQL("ALTER TABLE node RENAME TO node_old");
			database.execSQL("CREATE TABLE IF NOT EXISTS node (" +
					"id INTEGER PRIMARY KEY AUTOINCREMENT, " +
					"displayName TEXT, " +
					"addressName TEXT, " +
					"trusted INTEGER NOT NULL, " +
					"lastSeen INTEGER)"
			);
			database.execSQL("INSERT INTO node (id, displayName, addressName, trusted, lastSeen) " +
					"SELECT id, displayName, addressName, trusted, lastSeen FROM node_old");
			database.execSQL("DROP TABLE node_old");

			// 3. MESSAGE MIGRATION
			database.execSQL("ALTER TABLE message RENAME TO message_old");
			database.execSQL("CREATE TABLE IF NOT EXISTS message (" +
					"id INTEGER PRIMARY KEY AUTOINCREMENT, " +
					"content TEXT, " +
					"payloadId INTEGER, " +
					"senderNodeId INTEGER, " +
					"recipientNodeId INTEGER, " +
					"status INTEGER NOT NULL, " +
					"timestamp INTEGER NOT NULL, " +
					"type INTEGER NOT NULL, " +
					"FOREIGN KEY(senderNodeId) REFERENCES node(id) ON DELETE CASCADE, " +
					"FOREIGN KEY(recipientNodeId) REFERENCES node(id) ON DELETE CASCADE)"
			);
			database.execSQL("INSERT INTO message (id, content, payloadId, senderNodeId, recipientNodeId, status, timestamp, type) " +
					"SELECT id, content, payloadId, senderNodeId, recipientNodeId, status, timestamp, type FROM message_old");
			database.execSQL("DROP TABLE message_old");

			// 4. RESET INDICES
			database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_node_addressName ON node (addressName)");
			database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_message_payloadId ON message (payloadId)");
			database.execSQL("CREATE INDEX IF NOT EXISTS index_message_senderNodeId ON message (senderNodeId)");
			database.execSQL("CREATE INDEX IF NOT EXISTS index_message_recipientNodeId ON message (recipientNodeId)");

			database.execSQL("PRAGMA foreign_keys=ON");
		}
	};

	static final Migration MIGRATION_4_5 = new Migration(4, 5) {
		@Override
		public void migrate(@NonNull SupportSQLiteDatabase database) {
			database.execSQL("PRAGMA foreign_keys=OFF");

			// 1. DROP INDICES
			database.execSQL("DROP INDEX IF EXISTS index_message_payloadId");
			database.execSQL("DROP INDEX IF EXISTS index_message_senderNodeId");
			database.execSQL("DROP INDEX IF EXISTS index_message_recipientNodeId");

			// 2. MESSAGE MIGRATION
			database.execSQL("ALTER TABLE message RENAME TO message_old");
			database.execSQL("CREATE TABLE IF NOT EXISTS message (" +
					"id INTEGER PRIMARY KEY AUTOINCREMENT, " +
					"content TEXT, " +
					"payloadId INTEGER, " +
					"senderNodeId INTEGER, " +
					"lastAttempt INTEGER DEFAULT NULL, " +
					"recipientNodeId INTEGER, " +
					"status INTEGER NOT NULL, " +
					"timestamp INTEGER NOT NULL, " +
					"type INTEGER NOT NULL, " +
					"FOREIGN KEY(senderNodeId) REFERENCES node(id) ON DELETE CASCADE, " +
					"FOREIGN KEY(recipientNodeId) REFERENCES node(id) ON DELETE CASCADE)"
			);
			database.execSQL("INSERT INTO message (id, content, payloadId, senderNodeId, lastAttempt, recipientNodeId, status, timestamp, type) " +
					"SELECT id, content, payloadId, senderNodeId, NULL, recipientNodeId, status, timestamp, type FROM message_old");
			database.execSQL("DROP TABLE message_old");

			// 4. RESET INDICES
			database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_message_payloadId ON message (payloadId)");
			database.execSQL("CREATE INDEX IF NOT EXISTS index_message_senderNodeId ON message (senderNodeId)");
			database.execSQL("CREATE INDEX IF NOT EXISTS index_message_recipientNodeId ON message (recipientNodeId)");

			database.execSQL("PRAGMA foreign_keys=ON");
		}
	};

	private static volatile AppDatabase INSTANCE;

	public static AppDatabase getDB(Context context) {
		if (INSTANCE == null && context != null) {
			synchronized (AppDatabase.class) {
				if (INSTANCE == null) {
					System.loadLibrary("sqlcipher"); // Required to fix
					SupportOpenHelperFactory factory = new SupportOpenHelperFactory(AndroidKeyManager.getOrCreateAppCipherPassphrase(context));
					INSTANCE = Room.databaseBuilder(context.getApplicationContext(), AppDatabase.class, "satmesh_db")
							.openHelperFactory(factory)
							.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
							.build();
				}
			}
		}
		return INSTANCE;
	}

	public abstract NodeDao nodeDao();

	public abstract MessageDao messageDao();

	public abstract SignalSessionDao sessionDao();

	public abstract SignalPreKeyDao preKeyDao();

	public abstract SignalSignedPreKeyDao signedPreKeyDao();

	public abstract SignalIdentityKeyDao identityKeyDao();

	public abstract RouteEntryDao routeEntryDao();

	public abstract RouteUsageDao routeUsageDao();

	public abstract RouteRequestEntryDao routeRequestEntryDao();

	public abstract BroadcastStatusEntryDao broadcastStatusEntryDao();
}
