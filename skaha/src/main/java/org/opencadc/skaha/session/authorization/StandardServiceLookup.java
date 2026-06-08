package org.opencadc.skaha.session.authorization;

import java.net.URI;

/** Resolves a standard service ID to a concrete service URI. */
interface StandardServiceLookup {
    URI resolve(URI standardId);
}
