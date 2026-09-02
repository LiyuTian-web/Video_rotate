import { Button, Card } from "heroui-native";
import Ionicons from "@expo/vector-icons/Ionicons";
import type { JSX } from "react";
import { Text, View } from "react-native";
import { useCSSVariable } from "uniwind";

interface NoticeCardProps {
  title: string;
  description?: string;
  actionLabel?: string;
  onAction?: () => void;
}

export function NoticeCard({
  title,
  description,
  actionLabel,
  onAction,
}: NoticeCardProps): JSX.Element {
  const accent = useCSSVariable("--accent");
  return (
    <Card className="flex-row items-center gap-3 p-3">
      <View className="h-9 w-9 shrink-0 items-center justify-center rounded-full bg-accent-soft">
        <Ionicons
          name="information-circle-outline"
          size={20}
          color={typeof accent === "string" ? accent : "#3b82f6"}
        />
      </View>
      <View className="flex-1 gap-0.5">
        <Text className="text-sm font-semibold text-surface-foreground">{title}</Text>
        {description != null && <Text className="text-xs text-muted">{description}</Text>}
      </View>
      {actionLabel != null && onAction != null && (
        <Button size="sm" variant="secondary" onPress={onAction}>
          {actionLabel}
        </Button>
      )}
    </Card>
  );
}
