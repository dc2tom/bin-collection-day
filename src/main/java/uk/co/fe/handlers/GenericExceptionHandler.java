package uk.co.fe.handlers;

import com.amazon.ask.dispatcher.exception.ExceptionHandler;
import com.amazon.ask.dispatcher.request.handler.HandlerInput;
import com.amazon.ask.model.Response;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GenericExceptionHandler implements ExceptionHandler {
    private static Logger LOG = LoggerFactory.getLogger(SessionEndedRequestHandler.class);

    @Override
    public boolean canHandle(HandlerInput input, Throwable throwable) {
        return true;
    }

    @Override
    public Optional<Response> handle(HandlerInput input, Throwable throwable) {
        LOG.error("Exception handled: " +  throwable.getMessage());
        return input.getResponseBuilder()
                .withSpeech("Oh dear. " + throwable.getMessage())
                .withSimpleCard("Bin Collection Cheshire East", throwable.getMessage())
                .withShouldEndSession(true)
                .build();
    }
}
