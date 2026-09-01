import type { JSX } from "react";
import { View } from "react-native";

export function ProgressBar({ ratio }: { ratio: number }): JSX.Element {
  const percent = Math.max(0, Math.min(1, ratio)) * 100;
  return (
    <View className="h-1.5 overflow-hidden rounded-full bg-surface-tertiary">
      <View className="h-full rounded-full bg-accent" style={{ width: `${percent}%` }} />
    </View>
  );
}
