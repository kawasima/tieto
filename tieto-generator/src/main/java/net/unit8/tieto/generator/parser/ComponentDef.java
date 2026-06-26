package net.unit8.tieto.generator.parser;

/**
 * A single component of a resolved domain type (a record component or field),
 * used to describe Specification leaves and composites to the AI.
 */
public record ComponentDef(String name, String type) {}
