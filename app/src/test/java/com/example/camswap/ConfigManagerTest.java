package com.example.camswap;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

@RunWith(MockitoJUnitRunner.class)
public class ConfigManagerTest {

    @Mock
    Context mockContext;
    @Mock
    ContentResolver mockContentResolver;
    @Mock
    Cursor mockCursor;
    @Mock
    Uri mockUri;

    private ConfigManager configManager;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        when(mockContext.getContentResolver()).thenReturn(mockContentResolver);
        
        // ConfigManager constructor calls reload(), which calls reloadFromFile() if context is null.
        // We expect static block to handle Environment exception.
        configManager = new ConfigManager();
    }

    @Test
    public void testReloadFromProvider_Success() {
        try (MockedStatic<Uri> mockedUri = mockStatic(Uri.class)) {
            mockedUri.when(() -> Uri.parse("content://com.example.camswap.provider/config"))
                    .thenReturn(mockUri);

            // Mock Cursor data
            when(mockContentResolver.query(any(Uri.class), any(), any(), any(), any()))
                    .thenReturn(mockCursor);
            
            // Mocking cursor sequence
            // First call to moveToNext returns true (row 1)
            // Second call returns true (row 2)
            // Third call returns false
            when(mockCursor.moveToNext()).thenReturn(true, true, false);
            
            // Mock column data
            // We need to use doAnswer or multiple whens depending on call order
            // Simplified: Assuming ConfigManager calls getString(0), then (1), then (2) for each row
            
            when(mockCursor.getString(0)).thenReturn("key_bool", "key_int");
            when(mockCursor.getString(1)).thenReturn("true", "123");
            when(mockCursor.getString(2)).thenReturn("boolean", "int");

            configManager.setContext(mockContext);
            // setContext triggers reload() -> reloadFromProvider()

            JSONObject data = configManager.getConfigData();
            assertNotNull(data);
            assertTrue(data.has("key_bool"));
            assertTrue(data.getBoolean("key_bool"));
            assertTrue(data.has("key_int"));
            assertEquals(123, data.getInt("key_int"));
        } catch (Exception e) {
            fail("Exception: " + e.getMessage());
        }
    }
    
    @Test
    public void testReloadFromProvider_Failure_Fallback() {
         try (MockedStatic<Uri> mockedUri = mockStatic(Uri.class)) {
            mockedUri.when(() -> Uri.parse("content://com.example.camswap.provider/config"))
                    .thenReturn(mockUri);

            // Mock Cursor null (provider failed)
            when(mockContentResolver.query(any(Uri.class), any(), any(), any(), any()))
                    .thenReturn(null);

            configManager.setContext(mockContext);
            
            // Should fallback to file (which likely fails in test env, resulting in empty or default config)
            assertNotNull(configManager.getConfigData());
        }
    }
}
