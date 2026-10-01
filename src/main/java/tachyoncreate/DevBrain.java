package tachyoncreate;

import tachyon.api.BrainProvider;
import tachyon.api.BrainProviders;
import tachyon.api.ToolSpec;
import tachyon.api.Turn;

import java.util.ArrayList;
import java.util.List;

/**
 * A brain for trying the tools by hand on a development server, with no model: told
 * {@code crank {"seconds": 5}}, it calls that tool with those arguments and says what came of it;
 * told {@code tools}, it says which tools it is offered. Registered only when the game is started
 * with {@code -Dtachyon_create.dev=true} (the run in build.gradle), as the brain {@code script}:
 * {@code /bot Ada set brain script}, then {@code /bot Ada say crank {}}.
 */
final class DevBrain implements BrainProvider {

    static final String PROPERTY = "tachyon_create.dev";

    static void registerIfAsked() {
        if (Boolean.getBoolean(PROPERTY)) BrainProviders.register("script", new DevBrain());
    }

    @Override
    public void think(Turn turn) throws Exception {
        // News of an order and reports are only said: a tool runs only for words.
        if (turn.main().kind() != tachyon.api.Input.Kind.WORDS) return;
        String text = turn.main().text().trim();
        if (text.equals("tools")) {
            List<String> names = new ArrayList<>();
            for (ToolSpec t : turn.tools()) names.add(t.name());
            // The last ones first: an expansion's tools come after Tachyon's, and a bot says three lines at most.
            java.util.Collections.reverse(names);
            turn.say(names.size() + " tools: " + String.join(" ", names));
            return;
        }
        int space = text.indexOf(' ');
        String tool = space < 0 ? text : text.substring(0, space);
        String args = space < 0 ? "{}" : text.substring(space + 1).trim();
        turn.say(tool + ": " + turn.call(tool, args));
    }
}
