package au.com.shiftyjelly.pocketcasts.voicecontrol.fixture

/**
 * A framework resource attribute is real Kotlin syntax. It is not a chat lane called android,
 * so resolve() below must stay silent -- which is the boundary the guard exists to respect.
 */
class ResourceSyntax {
    fun resolve(): Int = android.R.color.transparent

    // A scoped package name is the same kind of thing: real syntax that merely starts with a lane.
    // The import is @cloudflare/workers-types in the TypeScript fixture.
}
