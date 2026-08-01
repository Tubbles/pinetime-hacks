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

        # Android SDK for building the phone-side clock-sync app (a Gradle port
        # of the DeskClock fork plus phone/clocksync/). DeskClock is pure
        # Java/androidx, so no NDK is needed.
        androidSdk = (pkgs.androidenv.composeAndroidPackages {
          platformVersions = [ "35" "34" ];
          buildToolsVersions = [ "35.0.0" ];
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

        devShells.default = self.devShells.${system}.android;
      });

  # Firmware (InfiniTime) is not built through Nix: it uses the official
  # image docker.io/infinitime/infinitime-build (nRF SDK 15.3.0 + ARM GCC
  # 10.3-2021.10 + mcuboot baked in). See doc/clock-sync-setup.md and
  # .github/workflows/firmware.yml.
}
