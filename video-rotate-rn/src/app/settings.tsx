import { useRouter } from "expo-router";
import { Button } from "heroui-native";
import Ionicons from "@expo/vector-icons/Ionicons";
import type { JSX } from "react";
import { useEffect, useState } from "react";
import { Pressable, ScrollView, Text, View } from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { useCSSVariable } from "uniwind";

import { SectionHeader } from "../components/section-header";
import type { ThemeMode } from "../lib/appearance";
import { loadThemeMode, saveThemeMode } from "../lib/appearance";

const themeModes: { value: ThemeMode; label: string }[] = [
  { value: "system", label: "跟随系统" },
  { value: "light", label: "浅色" },
  { value: "dark", label: "深色" },
];

function ThemePreview({ mode }: { mode: ThemeMode }): JSX.Element {
  const isLight = mode === "light";
  const isDark = mode === "dark";
  const bg = isDark ? "#18181b" : "#f4f4f5";
  const bar = isDark ? "#3f3f46" : "#d4d4d8";
  const title = isDark ? "#e4e4e7" : "#3f3f46";
  return (
    <View className="h-24 overflow-hidden rounded-xl border border-separator" style={{ backgroundColor: bg }}>
      {mode === "system" ? (
        <View className="absolute inset-y-0 right-0 w-1/2 bg-[#18181b]" />
      ) : null}
      <View className="flex-1 justify-center gap-2 p-3">
        <View className="h-2 w-1/2 rounded-full" style={{ backgroundColor: title }} />
        <View className="h-1.5 w-3/4 rounded-full" style={{ backgroundColor: bar }} />
        <View className="h-1.5 w-2/3 rounded-full" style={{ backgroundColor: bar }} />
        <View className="mt-1 flex-row items-center gap-1.5">
          <View className="h-5 w-10 rounded-full bg-accent" />
          <View className="h-1.5 w-6 rounded-full" style={{ backgroundColor: bar }} />
        </View>
      </View>
    </View>
  );
}

function ThemeCard({
  label,
  selected,
  mode,
  onPress,
}: {
  label: string;
  selected: boolean;
  mode: ThemeMode;
  onPress: () => void;
}): JSX.Element {
  const accent = useCSSVariable("--accent");
  const accentColor = typeof accent === "string" ? accent : "#3b82f6";
  return (
    <Pressable
      onPress={onPress}
      className={`flex-1 gap-2 rounded-2xl border-2 p-2 active:opacity-70 ${
        selected ? "border-accent bg-accent-soft" : "border-transparent bg-surface-secondary"
      }`}
    >
      <ThemePreview mode={mode} />
      <View className="flex-row items-center justify-center gap-1">
        {selected && <Ionicons name="checkmark-circle" size={14} color={accentColor} />}
        <Text className={`text-xs font-semibold ${selected ? "text-accent" : "text-muted"}`}>
          {label}
        </Text>
      </View>
    </Pressable>
  );
}

export default function SettingsScreen(): JSX.Element {
  const router = useRouter();
  const insets = useSafeAreaInsets();
  const [currentMode, setCurrentMode] = useState<ThemeMode>("system");
  const foreground = useCSSVariable("--foreground");
  const muted = useCSSVariable("--muted");
  const foregroundColor = typeof foreground === "string" ? foreground : "#000";
  const mutedColor = typeof muted === "string" ? muted : "#8e8e93";

  useEffect(() => {
    let active = true;
    void loadThemeMode().then((saved) => {
      if (active) setCurrentMode(saved);
    });
    return () => {
      active = false;
    };
  }, []);

  const selectMode = (next: ThemeMode): void => {
    setCurrentMode(next);
    void saveThemeMode(next);
  };

  return (
    <View
      className="flex-1 bg-background"
      style={{ paddingTop: insets.top + 4, paddingBottom: insets.bottom + 12 }}
    >
      <View className="flex-row items-center gap-1 px-2 py-1">
        <Button size="sm" variant="tertiary" onPress={() => router.back()}>
          <Ionicons name="chevron-back" size={16} color={foregroundColor} />
          <Text className="text-sm font-semibold text-foreground">返回</Text>
        </Button>
        <View className="flex-1 flex-row items-center justify-center gap-1.5 pr-16">
          <Ionicons name="settings-outline" size={20} color={foregroundColor} />
          <Text className="text-xl font-bold text-foreground">设置</Text>
        </View>
      </View>
      <View className="h-px bg-separator" />
      <ScrollView
        contentContainerClassName="gap-6 px-6 py-8"
        showsVerticalScrollIndicator={false}
      >
        <View className="gap-3">
          <SectionHeader icon="color-palette-outline" title="外观模式" />
          <View className="flex-row gap-3">
            {themeModes.map((item) => (
              <ThemeCard
                key={item.value}
                mode={item.value}
                label={item.label}
                selected={currentMode === item.value}
                onPress={() => selectMode(item.value)}
              />
            ))}
          </View>
        </View>

        <View className="h-px bg-separator" />

        <View className="gap-3">
          <SectionHeader icon="information-circle-outline" title="关于" />
          <Button
            size="md"
            variant="secondary"
            className="w-full justify-between"
            onPress={() => router.push("/about")}
          >
            <View className="flex-row items-center gap-2">
              <Ionicons name="apps-outline" size={16} color={foregroundColor} />
              <Text className="text-sm font-medium text-surface-secondary-foreground">
                关于本软件
              </Text>
            </View>
            <Ionicons name="chevron-forward" size={15} color={mutedColor} />
          </Button>
        </View>
      </ScrollView>
    </View>
  );
}
