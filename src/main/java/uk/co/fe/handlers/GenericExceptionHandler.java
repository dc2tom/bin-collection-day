package uk.co.fe.handlers;

import com.amazon.ask.dispatcher.exception.ExceptionHandler;
import com.amazon.ask.dispatcher.request.handler.HandlerInput;
import com.amazon.ask.model.Response;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;

public class GenericExceptionHandler implements ExceptionHandler {
    private static Logger LOG = LogManager.getLogger(SessionEndedRequestHandler.class);

    @Override
    public boolean canHandle(HandlerInput input, Throwable throwable) {
        return true;
    }

    @Override
    public Optional<Response> handle(HandlerInput input, Throwable throwable) {
        LOG.error("Exception handled: " +  throwable.getMessage());
        return input.getResponseBuilder()
                .withSpeech("An exception occurred.")
                .build();
    }
}
