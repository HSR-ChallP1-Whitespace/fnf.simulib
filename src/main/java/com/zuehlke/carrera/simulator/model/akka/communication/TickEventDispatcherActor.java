package com.zuehlke.carrera.simulator.model.akka.communication;

import akka.actor.Props;
import akka.actor.UntypedActor;
import com.zuehlke.carrera.simulator.model.akka.clock.Tick;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TickEventDispatcherActor extends UntypedActor {
    private static final Logger LOG = LoggerFactory.getLogger(TickEventDispatcherActor.class);
    private final NewsInterface newsInterface;

    private TickEventDispatcherActor(NewsInterface newsInterface) {
        this.newsInterface = newsInterface;
    }

    public static Props props(NewsInterface newsInterface) {
        return Props.create(TickEventDispatcherActor.class, () -> {
            return new TickEventDispatcherActor(newsInterface);
        });
    }

    @Override
    public void onReceive(Object message) throws Exception {
        if (message instanceof Tick) {
            handleTick((Tick) message);
        } else {
            unhandled(message);
        }
    }

    private void handleTick(Tick message) {
        try {
            tryHandleTick(message);
        } catch (Exception e) {
            LOG.warn(e.getMessage());
        }
    }

    private void tryHandleTick(Tick message) {
        newsInterface.tick(message);
    }
}
