package so.kontext.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.core.graphics.Insets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * The public API as Java code sees it: builders, the singleton, callbacks, a listener that implements only what
 * it needs, the sealed types and their objects. This file compiling is half the test.
 */
public class JavaUsageTest {
    @Test
    public void theModelsBuildFromJava() {
        Content content = new Content.Builder("ep-1", "en")
                .title("Episode 1")
                .durationMillis(92_000L)
                .episodeNumber(1)
                .series(new Content.Series.Builder("s").episodeCount(40).build())
                .mediaRating(Content.Rating.SAFE)
                .build();
        assertEquals("Episode 1", content.getTitle());
        assertEquals(Long.valueOf(92_000L), content.getDurationMillis());

        Configuration configuration = new Configuration.Builder("token")
                .logLevel(Configuration.LogLevel.DEBUG)
                .build();
        assertEquals(Configuration.DEFAULT_AD_SERVER_URL, configuration.getAdServerUrl());

        User user = new User.Builder().id("u-1").keywords(Collections.singletonList("drama")).build();
        assertEquals("u-1", user.getId());

        Regulatory regulatory = new Regulatory.Builder().coppa(true).gppSid(Arrays.asList(2, 7)).build();
        assertEquals(Boolean.TRUE, regulatory.getCoppa());
    }

    @Test
    public void theSealedTypesAreReachable() {
        VideoAd.RejectReason notUsed = VideoAd.RejectReason.NotUsed.INSTANCE;
        VideoAd.RejectReason outbid = new VideoAd.RejectReason.Outbid(2.5, "Other Network");
        VideoAd.RejectReason outbidUnknown = new VideoAd.RejectReason.Outbid(); // @JvmOverloads
        assertTrue(outbid instanceof VideoAd.RejectReason.Outbid);
        assertNull(((VideoAd.RejectReason.Outbid) outbidUnknown).getWinner());
        assertSame(VideoAd.RejectReason.NotUsed.INSTANCE, notUsed);

        VideoAdException error = new VideoAdException.NoFill(NoFillReason.FrequencyCap.INSTANCE);
        assertTrue(error instanceof Exception);
        assertSame(NoFillReason.FrequencyCap.INSTANCE, ((VideoAdException.NoFill) error).getReason());
        assertEquals("phone", "phone"); // keeps the import list honest

        assertEquals(VideoAd.EndReason.RESUMED, VideoAd.EndReason.valueOf("RESUMED"));
        assertEquals(AdFormat.LINEAR, AdFormat.valueOf("LINEAR"));
        assertTrue(KontextVideo.State.Enabled.INSTANCE instanceof KontextVideo.State);
        assertEquals(Insets.NONE, Insets.NONE);
    }

    @Test
    public void aListenerImplementsOnlyWhatItNeeds() {
        final String[] heard = new String[1];
        VideoAdListener listener = new VideoAdListener() {
            @Override
            public void onAdEnded(VideoAd ad, VideoAd.EndReason reason) {
                heard[0] = reason.name();
            }
        };
        listener.onAdEnded(null, VideoAd.EndReason.CLOSED);
        assertEquals("CLOSED", heard[0]);

        VideoAdsCallback<VideoAd> callback = new VideoAdsCallback<VideoAd>() {
            @Override
            public void onSuccess(VideoAd result) {
                heard[0] = "success";
            }

            @Override
            public void onError(VideoAdException error) {
                heard[0] = error.getMessage();
            }
        };
        callback.onError(new VideoAdException.NotLoaded());
        assertTrue(heard[0].contains("load()"));

        KontextVideo.InitializationListener initialized = state -> heard[0] = state.toString();
        initialized.onInitialized(KontextVideo.State.Failed.INSTANCE);
        assertEquals("Failed", heard[0]);
    }

    @Test
    public void aPlayerAdapterNeedsTwoMembers() {
        VideoAdsPlayer player = new VideoAdsPlayer() {
            @Override
            public long getCurrentPositionMillis() {
                return 1_000L;
            }

            @Override
            public boolean isPlaying() {
                return true;
            }
        };
        assertNull(player.getPlaybackSpeed());
        VideoAdsPlayer.Listener listener = isPlaying -> { };
        player.addListener(listener);
        player.removeListener(listener);
        assertEquals(1_000L, player.getCurrentPositionMillis());
        assertTrue(KontextVideo.VERSION.length() > 0);
        List<String> keys = Collections.emptyList();
        Map<String, String> labels = Collections.singletonMap("stage", "unlock");
        assertEquals(0, keys.size());
        assertEquals("unlock", labels.get("stage"));
    }
}
