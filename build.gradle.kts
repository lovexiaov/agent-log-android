// Root build for the AgentLog Android (Kotlin) logging framework.
// Orchestrates two modules:
//   - :agentlog-core      pure Kotlin/JVM, zero third-party deps, runs without Android SDK
//   - :agentlog-android   AGP library, depends on :agentlog-core
// Each module owns its build logic in its own build.gradle.kts.
