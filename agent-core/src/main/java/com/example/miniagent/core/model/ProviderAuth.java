package com.example.miniagent.core.model;

/** Availability only: implementations keep credentials outside model selections. */
@FunctionalInterface
public interface ProviderAuth {

	boolean available();

}
