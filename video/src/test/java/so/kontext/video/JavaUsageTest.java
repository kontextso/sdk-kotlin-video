package so.kontext.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.ViewGroup;
import androidx.core.graphics.Insets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Method;
import org.junit.Test;

/**
 * The public API as Java code sees it: builders, the singleton, callbacks, a listener that implements only what
 * it needs, the sealed types and their objects. This file compiling is half the test.
 */
public class JavaUsageTest {
    @Test
    public void theModelsBuildFromJava() {
        KontextVideo.Content content = new KontextVideo.Content.Builder("ep-1", "en")
                .title("Episode 1")
                .durationMillis(92_000L)
                .episodeNumber(1)
                .series(new KontextVideo.Content.Series.Builder("s").episodeCount(40).build())
                .mediaRating(KontextVideo.Content.Rating.SAFE)
                .build();
        assertEquals("Episode 1", content.getTitle());
        assertEquals(Long.valueOf(92_000L), content.getDurationMillis());

        KontextVideo.Configuration configuration = new KontextVideo.Configuration.Builder("token")
                .logLevel(KontextVideo.Configuration.LogLevel.DEBUG)
                .build();
        assertEquals(KontextVideo.Configuration.DEFAULT_AD_SERVER_URL, configuration.getAdServerUrl());

        KontextVideo.User user = new KontextVideo.User.Builder().id("u-1").keywords(Collections.singletonList("drama")).build();
        assertEquals("u-1", user.getId());

        KontextVideo.Regulatory regulatory = new KontextVideo.Regulatory.Builder().coppa(true).gppSid(Arrays.asList(2, 7)).build();
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

        VideoAdException error = new VideoAdException.NoFill(VideoAdException.NoFillReason.FrequencyCap.INSTANCE);
        assertTrue(error instanceof Exception);
        assertSame(VideoAdException.NoFillReason.FrequencyCap.INSTANCE, ((VideoAdException.NoFill) error).getReason());

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

    /**
     * A Java publisher's show, reject and dismiss: show throws the checked VideoAdException, so it compiles only
     * inside its try; reject and dismiss throw nothing (a no-op on the wrong ad is logged) and need none. Never
     * run: the methods are checked below; compiling is the test.
     */
    @SuppressWarnings("unused")
    private static String publisherCode(VideoAd overlay, VideoAd linear, ViewGroup container, Activity activity) {
        overlay.reject(); // @JvmOverloads: NotUsed
        linear.reject(new VideoAd.RejectReason.Outbid(2.5, "Other Network"));
        overlay.dismiss();
        try {
            overlay.show(container); // @JvmOverloads: no insets
            overlay.show(container, Insets.of(0, 0, 0, 120));
            linear.show(activity);
            return "shown";
        } catch (VideoAdException.PresenterUnavailable error) {
            return "later";
        } catch (VideoAdException error) {
            return error.getMessage();
        }
    }

    @Test
    public void showDeclaresTheCheckedExceptionRejectAndDismissDoNot() throws Exception {
        Method[] throwing = {
            VideoAd.class.getMethod("show", ViewGroup.class),
            VideoAd.class.getMethod("show", ViewGroup.class, Insets.class),
            VideoAd.class.getMethod("show", Activity.class),
        };
        for (Method method : throwing) {
            assertTrue(method + " throws VideoAdException", Arrays.asList(method.getExceptionTypes()).contains(VideoAdException.class));
        }
        Method[] quiet = {
            VideoAd.class.getMethod("reject"),
            VideoAd.class.getMethod("reject", VideoAd.RejectReason.class),
            VideoAd.class.getMethod("dismiss"),
        };
        for (Method method : quiet) {
            assertEquals(method + " declares no checked exception", 0, method.getExceptionTypes().length);
        }
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
