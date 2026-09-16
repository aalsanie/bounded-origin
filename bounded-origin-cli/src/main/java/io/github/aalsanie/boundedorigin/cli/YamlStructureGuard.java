package io.github.aalsanie.boundedorigin.cli;

import java.util.ArrayDeque;
import java.util.Deque;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Parse;
import org.snakeyaml.engine.v2.events.AliasEvent;
import org.snakeyaml.engine.v2.events.CollectionStartEvent;
import org.snakeyaml.engine.v2.events.DocumentStartEvent;
import org.snakeyaml.engine.v2.events.Event;
import org.snakeyaml.engine.v2.events.MappingEndEvent;
import org.snakeyaml.engine.v2.events.NodeEvent;
import org.snakeyaml.engine.v2.events.ScalarEvent;
import org.snakeyaml.engine.v2.events.SequenceEndEvent;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;

final class YamlStructureGuard {
  private YamlStructureGuard() {}

  static void validate(String yaml, LoadSettings settings) throws ConfigurationException {
    int documents = 0;
    int nodes = 0;
    Deque<Integer> collectionNodes = new ArrayDeque<>();
    try {
      for (Event event : new Parse(settings).parseString(yaml)) {
        if (event instanceof DocumentStartEvent start) {
          documents++;
          if (documents > 1) {
            throw new ConfigurationException(
                "configuration must contain exactly one YAML document");
          }
          if (start.getSpecVersion().isPresent() || !start.getTags().isEmpty()) {
            throw new ConfigurationException("YAML directives are not supported");
          }
          continue;
        }
        if (event instanceof AliasEvent) {
          throw new ConfigurationException("YAML aliases are not supported");
        }
        if (event instanceof CollectionStartEvent start) {
          rejectNodeProperties(start);
          nodes = incrementTotalNodes(nodes);
          incrementParent(collectionNodes);
          if (collectionNodes.size() + 1 > ConfigurationLimits.MAX_DEPTH) {
            throw new ConfigurationException("YAML nesting depth exceeds configured limit");
          }
          collectionNodes.push(0);
          continue;
        }
        if (event instanceof ScalarEvent scalar) {
          rejectNodeProperties(scalar);
          nodes = incrementTotalNodes(nodes);
          incrementParent(collectionNodes);
          if (scalar.getValue().codePointCount(0, scalar.getValue().length())
              > ConfigurationLimits.MAX_SCALAR_CODE_POINTS) {
            throw new ConfigurationException("YAML scalar exceeds configured limit");
          }
          continue;
        }
        if (event instanceof MappingEndEvent || event instanceof SequenceEndEvent) {
          if (collectionNodes.isEmpty()) {
            throw new ConfigurationException("invalid YAML collection structure");
          }
          collectionNodes.pop();
        }
      }
    } catch (YamlEngineException exception) {
      throw new ConfigurationException("invalid YAML configuration", exception);
    }
    if (documents != 1 || !collectionNodes.isEmpty()) {
      throw new ConfigurationException(
          "configuration must contain exactly one complete YAML document");
    }
  }

  private static void rejectNodeProperties(NodeEvent event) throws ConfigurationException {
    if (event.getAnchor().isPresent()) {
      throw new ConfigurationException("YAML anchors are not supported");
    }
    if (event instanceof ScalarEvent scalar && scalar.getTag().isPresent()) {
      throw new ConfigurationException("explicit YAML tags are not supported");
    }
    if (event instanceof CollectionStartEvent collection && collection.getTag().isPresent()) {
      throw new ConfigurationException("explicit YAML tags are not supported");
    }
  }

  private static int incrementTotalNodes(int nodes) throws ConfigurationException {
    int next = nodes + 1;
    if (next > ConfigurationLimits.MAX_TOTAL_NODES) {
      throw new ConfigurationException("YAML node count exceeds configured limit");
    }
    return next;
  }

  private static void incrementParent(Deque<Integer> collectionNodes)
      throws ConfigurationException {
    if (collectionNodes.isEmpty()) {
      return;
    }
    int next = collectionNodes.pop() + 1;
    if (next > ConfigurationLimits.MAX_COLLECTION_NODES) {
      throw new ConfigurationException("YAML collection exceeds configured limit");
    }
    collectionNodes.push(next);
  }
}
