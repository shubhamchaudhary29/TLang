package dev.tlang.packages;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ManifestCodecTest {
    @Test void parsesValidManifestAndMultilineDependencies() {
        PackageManifest manifest = ManifestCodec.parse("""
            # project metadata
            [package]
            name = "my-api"
            version = "0.1.0-beta.1+build"

            [dependencies]
            utils = { path = "../utils" }
            http_helpers = {
              git = "https://example.test/http_helpers.git",
              rev = "main"
            }
            """, "tlang.toml");
        assertEquals("my-api", manifest.name());
        assertEquals(2, manifest.dependencies().size());
        assertEquals(DependencySpec.SourceType.PATH, manifest.dependencies().get("utils").sourceType());
        assertEquals("main", manifest.dependencies().get("http_helpers").revision());
    }

    @Test void emptyDependenciesAreValidAndWriterIsStable() {
        PackageManifest manifest = ManifestCodec.parse("""
            [package]
            name = "app"
            version = "1.0.0"
            [dependencies]
            """, "tlang.toml");
        assertTrue(manifest.dependencies().isEmpty());
        assertEquals(ManifestCodec.write(manifest), ManifestCodec.write(
            ManifestCodec.parse(ManifestCodec.write(manifest), "roundtrip")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "name = \"app\"\nversion = \"1.0.0\"",
        "[package]\nversion = \"1.0.0\"",
        "[package]\nname = \"Bad Name\"\nversion = \"1.0.0\"",
        "[package]\nname = \"app\"\nversion = \"01.0.0\"",
        "[package]\nname = \"app\"\nversion = \"1.0.0-01\"",
        "[package]\nname = \"app\"\nversion = \"1.0.0\"\nunknown = \"x\"",
        "[unknown]\nname = \"app\"\nversion = \"1.0.0\"",
        "[package]\nname = \"app\"\nversion = \"1.0.0\"\n[package]",
        "[package]\nname = \"app\"\nname = \"again\"\nversion = \"1.0.0\"",
        "[package]\nname = \"app\"\nversion = \"1.0.0\"\n[dependencies]\nx = {}",
        "[package]\nname = \"app\"\nversion = \"1.0.0\"\n[dependencies]\nx = { path = \"x\", git = \"https://e.test/x\", rev = \"main\" }",
        "[package]\nname = \"app\"\nversion = \"1.0.0\"\n[dependencies]\nx = { git = \"https://e.test/x\" }",
        "[package]\nname = \"app\"\nversion = \"1.0.0\"\n[dependencies]\nx = { git = \"not-a-url\", rev = \"main\" }",
        "[package]\nname = \"app\"\nversion = \"1.0.0\"\n[dependencies]\nx = { git = \"https://e.test/x\", rev = \"-u\" }",
        "[package]\nname = \"app\"\nversion = \"1.0.0\"\n[dependencies]\nbad-name = { path = \"x\" }",
        "[package]\nname = \"app\"\nversion = \"1.0.0\"\n[dependencies]\nx = { path = \"/absolute\" }",
        "[package]\nname = \"app\"\nversion = \"1.0.0\"\n[dependencies]\nx = { path = \"C:\\\\absolute\" }",
        "[package]\nname = \"app\"\nversion = \"1.0.0\"\n[dependencies]\nx = { path = \"x\", surprise = \"yes\" }"
    })
    void rejectsMalformedOrAmbiguousManifest(String text) {
        assertThrows(PackageException.class, () -> ManifestCodec.parse(text, "bad.toml"));
    }

    @Test void rejectsDuplicateDependencyAndControlCharacter() {
        String duplicate = "[package]\nname=\"app\"\nversion=\"1.0.0\"\n[dependencies]\nx={path=\"x\"}\nx={path=\"y\"}";
        assertTrue(assertThrows(PackageException.class, () -> ManifestCodec.parse(duplicate, "bad")).getMessage().contains("duplicate"));
        String control = "[package]\nname=\"app\"\nversion=\"1.0.0\"\u0000";
        assertThrows(PackageException.class, () -> ManifestCodec.parse(control, "bad"));
        assertThrows(PackageException.class, () -> ManifestCodec.parse(
            "[package]\nname=\"app\ncontinued\"\nversion=\"1.0.0\"", "bad"));
    }

    @Test void selfDependencyIsRejected() {
        String text = "[package]\nname=\"app\"\nversion=\"1.0.0\"\n[dependencies]\napp={path=\".\"}";
        assertTrue(assertThrows(PackageException.class, () -> ManifestCodec.parse(text, "bad")).getMessage().contains("itself"));
    }
}
