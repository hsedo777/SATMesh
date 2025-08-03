package org.sedo.satmesh.nearby.data;

import androidx.annotation.Nullable;

/**
 * Helper callback for operation of fetching of data from the database.
 *
 * @param <T> The type of the data to be fetched from the database.
 */
public interface ResultHandler<T> {
	/**
	 * Called when the operation terminated.
	 *
	 * @param result The fetched object.
	 */
	void onTerminated(@Nullable T result);
}
