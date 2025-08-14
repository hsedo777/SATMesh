package org.sedo.satmesh.utils;

/**
 * A generic class that holds two objects of potentially different types.
 *
 * @param <T> The type of the first object.
 * @param <V> The type of the second object.
 */
public class BiObjectHolder<T, V> {

	private T first;
	private V second;

	/**
	 * Sets the two objects held by this instance.
	 * This method is synchronized to ensure thread safety.
	 *
	 * @param first The first object.
	 * @param second The second object.
	 */
	public synchronized void post(T first, V second) {
		this.first = first;
		this.second = second;
	}

	/**
	 * Gets the first object held by this instance.
	 * This method is synchronized to ensure thread safety.
	 *
	 * @return The first object.
	 */
	public synchronized T getFirst() {
		return first;
	}

	/**
	 * Gets the second object held by this instance.
	 * This method is synchronized to ensure thread safety.
	 *
	 * @return The second object.
	 */
	public synchronized V getSecond() {
		return second;
	}
}
