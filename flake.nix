{
  description = "pinetime-hacks: InfiniTime firmware fork + phone-side clock-sync bridge";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };

        # Android SDK for building the phone-side apps: the Clock fork
        # (compileSdk 35) and the Fossify Phone dialer fork (compileSdk 36).
        # Pure Java/Kotlin + androidx, so no NDK is needed.
        androidSdk = (pkgs.androidenv.composeAndroidPackages {
          platformVersions = [ "36" "35" "34" ];
          buildToolsVersions = [ "36.1.0" "36.0.0" "35.0.0" ];
          includeNDK = false;
        }).androidsdk;
      in {
        devShells.android = pkgs.mkShell {
          packages = with pkgs; [
            androidSdk
            openjdk17
            gradle_8
          ];
          ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
          ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";
        };

        # A plain JDK shell for the pure ClockSyncFrame codec test (no SDK).
        devShells.jvm = pkgs.mkShell {
          packages = [ pkgs.openjdk17 ];
        };

        # Gadgetbridge T needs a newer toolchain than the other apps: JDK 21
        # and build-tools 36.1.0. Gradle 9.5.1 comes from the repo's own
        # wrapper (network fetch on first run), so no nix gradle here.
        devShells.gadgetbridge = pkgs.mkShell {
          packages = [ androidSdk pkgs.openjdk21 ];
          ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
          ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";
          JAVA_HOME = "${pkgs.openjdk21}/lib/openjdk";
        };

        devShells.default = self.devShells.${system}.android;
      });

  # Firmware (InfiniTime) is not built through Nix: it uses the official
  # image docker.io/infinitime/infinitime-build (nRF SDK 15.3.0 + ARM GCC
  # 10.3-2021.10 + mcuboot baked in). See doc/clock-sync-setup.md and
  # .github/workflows/firmware.yml.
}
