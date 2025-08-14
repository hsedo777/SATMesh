package org.sedo.satmesh.utils;

/**
 * A generic class that holds a single object.
 * This class is thread-safe.
 *
 * @param <T> The type of the object to hold.
 */
public class ObjectHolder<T> {
	private T value;

	/**
	 * Sets the value of the object.
	 *
	 * @param value The value to set.
	 */
	public synchronized void post(T value) {
		this.value = value;
	}

	/**
	 * Gets the value of the object.
	 *
	 * @return The value of the object.
	 */
	public synchronized T getValue() {
		return value;
	}
}
