package org.sedo.satmesh.model;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Fts4;
import androidx.room.PrimaryKey;

/**
 * FTS (Full-Text Search) table for message content.
 * This entity is used by Room to create a virtual FTS table for efficient text searching.
 * It mirrors the relevant columns from the Message entity for searching purposes.
 */
@Fts4(contentEntity = Message.class)
@Entity(tableName = "message_fts")
public class MessageFts {
	// Primary key for the FTS table, it's usually the rowid from the contentEntity
	@PrimaryKey(autoGenerate = true)
	@ColumnInfo(name = "rowid")
	private int rowId;

	// The column to search on
	@ColumnInfo(name = "content")
	private String content;

	@ColumnInfo(name = "senderNodeId")
	private Long senderNodeId;

	@ColumnInfo(name = "recipientNodeId")
	private Long recipientNodeId;
}