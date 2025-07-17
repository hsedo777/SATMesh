package org.sedo.satmesh.model;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
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
	 *
	 * @param node The Node object to insert.
	 * @return The row ID of the newly inserted Node, or -1 if the conflict strategy was IGNORE.
	 */
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	long insert(Node node);

	/**
	 * Updates an existing Node in the database.
	 *
	 * @param node The Node object to update.
	 */
	@Update
	void update(Node node);

	/**
	 * Retrieves a Node by its unique Signal Protocol address name.
	 * This is useful for finding a specific contact or the local host node.
	 *
	 * @param addressName The Signal Protocol address name of the Node.
	 * @return The Node object, or null if not found.
	 */
	@Query("SELECT * FROM node WHERE addressName = :addressName")
	Node getNodeByAddressNameSync(String addressName);

	@Query("SELECT * FROM node WHERE addressName IN (:addresses)")
	LiveData<List<Node>> getNodesByAddressName(List<String> addresses);

	/**
	 * Retrieves a Node by its primary key ID.
	 *
	 * @param id The primary key ID of the Node.
	 * @return The Node object, or null if not found.
	 */
	@Query("SELECT * FROM node WHERE id = :id")
	Node getNodeByIdSync(Long id);

	/**
	 * Retrieves a Node by its primary key ID and wraps it in a `LiveData`.
	 *
	 * @param id The primary key ID of the Node.
	 * @return The Node object, or null if not found.
	 */
	@Query("SELECT * FROM node WHERE id = :id")
	LiveData<Node> getNodeById(long id);

	@Delete
	int delete(List<Node> nodes); // For deleting multiple nodes (useful for selected items)

	/**
	 * Retrieves all known Node objects from the database, excluding the host node.
	 * The nodes are ordered by their display name for a consistent list.
	 *
	 * @param hostNodeId The ID of the current host node, which should be excluded from the list.
	 * @return A LiveData list of Node objects that are not the host node.
	 */
	@Query("SELECT * FROM node WHERE id != :hostNodeId ORDER BY displayName COLLATE NOCASE ASC")
	LiveData<List<Node>> getKnownNodesExcludingHost(long hostNodeId);
}