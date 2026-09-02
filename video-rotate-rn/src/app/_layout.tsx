import { Stack } from "expo-router";
import { StatusBar } from "expo-status-bar";
import { HeroUINativeProvider } from "heroui-native";
import type { JSX } from "react";
import { useEffect } from "react";
import { GestureHandlerRootView } from "react-native-gesture-handler";
import { SafeAreaProvider } from "react-native-safe-area-context";
import { Uniwind, useUniwind } from "uniwind";

import "../global.css";
import { loadThemeMode } from "../lib/appearance";

export default function RootLayout(): JSX.Element {
  const { theme } = useUniwind();

  useEffect(() => {
    void loadThemeMode().then((mode) => Uniwind.setTheme(mode));
  }, []);

  return (
    <GestureHandlerRootView style={{ flex: 1 }}>
      <SafeAreaProvider>
        <HeroUINativeProvider
          config={{
            toast: {
              defaultProps: {
                placement: "top",
              },
            },
          }}
        >
          <StatusBar style={theme === "dark" ? "light" : "dark"} />
          <Stack screenOptions={{ headerShown: false }} />
        </HeroUINativeProvider>
      </SafeAreaProvider>
    </GestureHandlerRootView>
  );
}
