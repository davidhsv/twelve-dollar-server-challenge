package feed;

import server.Environment;
import server.Server;
import server.ServerConfig;

/** Starts the feed: the server's settings and JWT_SECRET from the environment, then the server. */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        long descriptorLimit = Server.raiseDescriptorLimit();
        ServerConfig config;
        try {
            config = ServerConfig.fromEnvironment(descriptorLimit);
        } catch (IllegalArgumentException e) {
            Server.log(e.getMessage());
            System.exit(1);
            return;
        }
        Server.run(config, new FeedApplication(Environment.get("JWT_SECRET", "")));
    }
}
