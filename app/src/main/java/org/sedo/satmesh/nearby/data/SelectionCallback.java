package org.sedo.satmesh.nearby.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Helper callback for operation of fetching of data from the database.
 *
 * @param <T> The type of the data to be fetched from the database.
 */
public interface SelectionCallback<T> {
	/**
	 * Called when a selection is made successfully with non null result.
	 *
	 * @param selection The fetched object from the database.
	 */
	void onSuccess(@NonNull T selection);

	/**
	 * Called if an exception occurred on selection processing or if there is no matching
	 * in the database (in that case {@code cause} is {@code null}).
	 *
	 * @param cause The cause on failure.
	 */
	void onFailure(@Nullable Exception cause);
}