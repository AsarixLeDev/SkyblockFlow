package dev.asarix.flow.update;

import java.io.InputStream;
import java.net.URI;
import java.util.List;

/** Optional account-backed release source supplied by the host distribution. */
interface AccountFeed {
    String name();
    String repository();
    List<Release> releases(boolean includeBeta) throws Exception;
    InputStream asset(URI uri) throws Exception;
}
