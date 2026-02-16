package com.example.camswap;

import static org.junit.Assert.assertEquals;

import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class VideoProviderTest {

    @Test
    public void testConstants() {
        assertEquals("com.example.camswap.provider", VideoProvider.AUTHORITY);
        assertEquals("video", VideoProvider.PATH_VIDEO);
        assertEquals("config", VideoProvider.PATH_CONFIG);
        assertEquals("next", VideoProvider.METHOD_NEXT);
        assertEquals("prev", VideoProvider.METHOD_PREV);
        assertEquals("random", VideoProvider.METHOD_RANDOM);
    }
    
    // Note: Deeper testing of VideoProvider requires Robolectric or instrumentation tests
    // because it heavily relies on Android framework classes (ContentProvider, MatrixCursor, etc.)
    // which are stubs in standard JUnit tests.
}
