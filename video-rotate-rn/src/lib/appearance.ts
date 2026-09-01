import AsyncStorage from "@react-native-async-storage/async-storage";
import { Uniwind } from "uniwind";

export type ThemeMode = "system" | "light" | "dark";

const STORAGE_KEY = "theme-mode";

export const loadThemeMode = async (): Promise<ThemeMode> =>
  ((await AsyncStorage.getItem(STORAGE_KEY)) as ThemeMode | null) ?? "system";

export const saveThemeMode = async (mode: ThemeMode): Promise<void> => {
  Uniwind.setTheme(mode);
  await AsyncStorage.setItem(STORAGE_KEY, mode);
};
