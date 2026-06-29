package com.hh.examples.study12.productionBestPractices;

import com.google.adk.events.Event;

import java.util.List;

public record ProductionEventSignal(
        String author,
        boolean finalResponse,
        List<String> functionCalls,
        List<String> functionResponses,
        List<String> stateDeltaKeys) {

    public static ProductionEventSignal from(Event event) {
        return new ProductionEventSignal(
                event.author(),
                event.finalResponse(),
                event.functionCalls().stream()
                        .map(functionCall -> functionCall.name().orElse("unknown"))
                        .toList(),
                event.functionResponses().stream()
                        .map(functionResponse -> functionResponse.name().orElse("unknown"))
                        .toList(),
                event.actions().stateDelta().keySet().stream().toList());
    }
}
