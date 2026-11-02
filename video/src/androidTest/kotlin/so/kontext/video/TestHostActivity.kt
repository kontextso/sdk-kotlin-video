package so.kontext.video

import android.app.Activity

/** A plain screen for tests that lay out views in a real window. */
class TestHostActivity : Activity()

/** Another opaque screen of the app, started over a test's host. */
class CoverActivity : Activity()

/** A translucent screen of the app over a test's host: the host pauses and stays drawn. */
class TranslucentCoverActivity : Activity()
