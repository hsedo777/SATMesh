package org.sedo.satmesh.utils;

public class ObjectHolder<T> {
	private T value;

	public synchronized void post(T value) {
		this.value = value;
	}

	public synchronized T getValue() {
		return value;
	}
}
