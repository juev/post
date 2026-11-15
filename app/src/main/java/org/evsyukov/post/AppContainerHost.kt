package org.evsyukov.post

/**
 * An application that holds the [AppContainer]. [PostApplication] is one; JVM tests of activities give their own
 * application with a container built for the test.
 */
interface AppContainerHost {
    val container: AppContainer
}
