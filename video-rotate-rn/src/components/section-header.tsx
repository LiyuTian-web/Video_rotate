import Ionicons from "@expo/vector-icons/Ionicons";
import type { JSX } from "react";
import { Text, View } from "react-native";
import { useCSSVariable } from "uniwind";

type IconName = keyof typeof Ionicons.glyphMap;

export function SectionHeader({
  icon,
  title,
  className,
}: {
  icon: IconName;
  title: string;
  className?: string;
}): JSX.Element {
  const muted = useCSSVariable("--muted");
  return (
    <View className={`flex-row items-center gap-2 ${className ?? ""}`}>
      <Ionicons name={icon} size={18} color={typeof muted === "string" ? muted : "#8e8e93"} />
      <Text className="text-base font-bold text-foreground">{title}</Text>
    </View>
  );
}
