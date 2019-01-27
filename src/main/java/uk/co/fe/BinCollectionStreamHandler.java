package uk.co.fe;

import com.amazon.ask.Skill;
import com.amazon.ask.Skills;
import com.amazon.ask.SkillStreamHandler;
import uk.co.fe.handlers.GenericExceptionHandler;
import uk.co.fe.handlers.LaunchRequestHandler;
import uk.co.fe.handlers.SessionEndedRequestHandler;

public class BinCollectionStreamHandler extends SkillStreamHandler {

    private static Skill getSkill() {
        return Skills.standard()
                .addRequestHandlers(
                        new LaunchRequestHandler(),
                        new SessionEndedRequestHandler())
                .addExceptionHandler(new GenericExceptionHandler())
                .build();
    }

    public BinCollectionStreamHandler() {
        super(getSkill());
    }

}
