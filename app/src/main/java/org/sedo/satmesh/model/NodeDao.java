package org.sedo.satmesh.model;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

/**
 * Data Access Object (DAO) for the {@link Node} entity.
 * Provides methods for interacting with the 'node' table in the database.
 */
@Dao
public interface NodeDao {

	/**
	 * Inserts a new Node into the database. If a Node with the same addressName already exists,
	 * it will be ignored (OnConflictStrategy.IGNORE).
	 * @param node The Node object to insert.
	 * @return The row ID of the newly inserted Node, or -1 if the conflict strategy was IGNORE.
	 */
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	long insert(Node node);

	/**
	 * Updates an existing Node in the database.
	 * @param node The Node object to update.
	 * @return The number of rows updated.
	 */
	@Update
	int update(Node node);

	/**
	 * Retrieves a Node by its unique Signal Protocol address name.
	 * This is useful for finding a specific contact or the local host node.
	 * @param addressName The Signal Protocol address name of the Node.
	 * @return The Node object, or null if not found.
	 */
	@Query("SELECT * FROM node WHERE addressName = :addressName")
	Node getNodeByAddressName(String addressName);

	/**
	 * Retrieves a Node by its primary key ID.
	 * @param id The primary key ID of the Node.
	 * @return The Node object, or null if not found.
	 */
	@Query("SELECT * FROM node WHERE id = :id")
	Node getNodeById(Long id);

	/**
	 * Get the live data of nodes in state connected
	 * @see Node#isConnected()
	 */
	@Query("SELECT * FROM node WHERE connected = 1")
	LiveData<List<Node>> getConnectedNode();

	/**
	 * Retrieves all Nodes (contacts) from the database, excluding the local host node.
	 * This query assumes you'll know your own local addressName to exclude it.
	 * If you prefer to always fetch all nodes, remove the WHERE clause.
	 * @param localAddressName The Signal Protocol address name of the local host node to exclude.
	 * @return A LiveData list of all other Node objects, which can be observed for changes.
	 */
	@Query("SELECT * FROM node WHERE addressName != :localAddressName ORDER BY displayName ASC")
	LiveData<List<Node>> getAllOtherNodes(String localAddressName);

	/**
	 * Retrieves all Nodes (contacts) from the database.
	 * @return A LiveData list of all Node objects, which can be observed for changes.
	 */
	@Query("SELECT * FROM node ORDER BY displayName ASC")
	LiveData<List<Node>> getAllNodes();

	/**
	 * Deletes a specific Node from the database.
	 * @param addressName The name of the Node object to delete
	 * @return The number of rows deleted.
	 */
	@Query("DELETE FROM node WHERE addressName = :addressName")
	int deleteNodeByAddressName(String addressName);

	/**
	 * Deletes all Nodes from the database.
	 */
	@Query("DELETE FROM node")
	void deleteAllNodes();

	/**
	 * Searches for nodes (contacts) whose display name contains the provided query string.
	 * The search is case-insensitive.
	 * @param query The search query string (e.g., "Alice").
	 * @param localAddressName The Signal Protocol address name of the local host node to exclude from results.
	 * @return A LiveData list of matching Node objects.
	 */
	@Query("SELECT * FROM node WHERE displayName LIKE '%' || :query || '%' AND addressName != :localAddressName ORDER BY displayName ASC")
	LiveData<List<Node>> searchNodesByDisplayName(String query, String localAddressName);

	/**
	 * Searches for all nodes (including the local host) whose display name contains the provided query string.
	 * @param query The search query string.
	 * @return A LiveData list of all matching Node objects.
	 */
	@Query("SELECT * FROM node WHERE displayName LIKE '%' || :query || '%' ORDER BY displayName ASC")
	LiveData<List<Node>> searchAllNodesByDisplayName(String query);

	/**
	 * Retrieves all Nodes that are marked as trusted.
	 * These typically represent established contacts or trusted relays in the mesh network.
	 *
	 * @return A LiveData list of trusted Nodes, ordered by display name.
	 */
	@Query("SELECT * FROM node WHERE trusted = 1 ORDER BY displayName ASC")
	LiveData<List<Node>> getTrustedNodes();

	/**
	 * Retrieves all Nodes that are not marked as trusted.
	 * These usually represent newly discovered nodes that require user approval or further action.
	 *
	 * @return A LiveData list of untrusted Nodes, ordered by display name.
	 */
	@Query("SELECT * FROM node WHERE trusted = 0 ORDER BY displayName ASC")
	LiveData<List<Node>> getUntrustedNodes();

	/**
	 * Updates the trusted status of a specific Node by its database ID.
	 * This method is crucial for marking a newly approved connection as a trusted contact.
	 *
	 * @param nodeId The database ID of the node to update.
	 * @param trusted The new trusted status ({@code true} for trusted, {@code false} for untrusted).
	 * @return The number of rows affected (should be 1 for a successful update).
	 */
	@Query("UPDATE node SET trusted = :trusted WHERE id = :nodeId")
	int updateNodeTrustedStatus(long nodeId, boolean trusted);

	/**
	 * Checks if a Node with the given Signal Protocol address name is marked as trusted.
	 * This is used to decide whether to automatically accept a connection or prompt the user for approval.
	 *
	 * @param addressName The unique address name of the node to check.
	 * @return {@code true} if the node is trusted, {@code false} otherwise.
	 */
	@Query("SELECT EXISTS(SELECT 1 FROM node WHERE addressName = :addressName AND trusted = 1 LIMIT 1)")
	boolean isNodeTrusted(String addressName);
}