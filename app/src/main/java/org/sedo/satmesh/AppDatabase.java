package org.sedo.satmesh;


import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import net.zetetic.database.sqlcipher.SupportOpenHelperFactory;

import org.sedo.satmesh.model.Message;
import org.sedo.satmesh.model.MessageDao;
import org.sedo.satmesh.model.MessageFts;
import org.sedo.satmesh.model.Node;
import org.sedo.satmesh.model.NodeDao;
import org.sedo.satmesh.model.SignalKeyExchangeState;
import org.sedo.satmesh.model.SignalKeyExchangeStateDao;
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
		version = 3)
public abstract class AppDatabase extends RoomDatabase {

	public abstract NodeDao nodeDao();
	public abstract MessageDao messageDao();
	public abstract SignalSessionDao sessionDao();
	public abstract SignalPreKeyDao preKeyDao();
	public abstract SignalSignedPreKeyDao signedPreKeyDao();
	public abstract SignalIdentityKeyDao identityKeyDao();
	public abstract SignalKeyExchangeStateDao signalKeyExchangeStateDao();
	public abstract RouteEntryDao routeEntryDao();
	public abstract RouteUsageDao routeUsageDao();
	public abstract RouteRequestEntryDao routeRequestEntryDao();
	public abstract BroadcastStatusEntryDao broadcastStatusEntryDao();

	private static volatile AppDatabase INSTANCE;

	public static AppDatabase getDB(Context context){
		if (INSTANCE == null && context != null){
			synchronized (AppDatabase.class){
				if (INSTANCE == null){
					System.loadLibrary("sqlcipher"); // Required to fix
					SupportOpenHelperFactory factory = new SupportOpenHelperFactory(AndroidKeyManager.getOrCreateAppCipherPassphrase(context));
					INSTANCE = Room.databaseBuilder(context.getApplicationContext(), AppDatabase.class, "satmesh_db")
							.openHelperFactory(factory)
							//.addMigrations(MIGRATION_1_2,...)
							.fallbackToDestructiveMigration(true)//Only at dev stage
							.build();
				}
			}
		}
		return INSTANCE;
	}

	/* Sample of hook for actions to execute before db encryption or after db decryption
	private static final SQLiteDatabaseHook databaseHook = new SQLiteDatabaseHook() {
		@Override
		public void preKey(SQLiteConnection database) {
			// This method is called before db encryption
			// You can execute "PRAGMAS" here if needed before encryption
		}

		@Override
		public void postKey(SQLiteConnection database) {
			// Called after db encryption
			database.execSQL("PRAGMA cipher_memory_security = ON;");
			database.execSQL("PRAGMA secure_delete = ON;");
		}
	};

    // Define migrations here
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            // Implement schema changes here
            // Ex: database.execSQL("ALTER TABLE node ADD COLUMN newColumn TEXT");
        }
    };
    */
}
