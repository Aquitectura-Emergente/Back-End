package com.emsafe.iottelemetry.interfaces.rest.dto;

/**
 * Estado deseado del relé de un dispositivo (ON | OFF | null = sin orden).
 * Lo consume el edge para accionar el dispositivo físico.
 */
public record PlugStateDto(String desiredPlug) {}
