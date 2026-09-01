import * as Clipboard from "expo-clipboard";
import { useRouter } from "expo-router";
import { Button, Card, Chip } from "heroui-native";
import Ionicons from "@expo/vector-icons/Ionicons";
import type { JSX } from "react";
import { useEffect, useState } from "react";
import { Image, Linking, ScrollView, Text, View } from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { useCSSVariable } from "uniwind";

import type { ThemeMode } from "../lib/appearance";
import { loadThemeMode, saveThemeMode } from "../lib/appearance";

const AUTHOR_EMAIL = "woshitianyumi@outlook.com";

type IconName = keyof typeof Ionicons.glyphMap;

const themeModes: { value: ThemeMode; label: string; icon: IconName }[] = [
  { value: "system", label: "跟随系统", icon: "phone-portrait-outline" },
  { value: "light", label: "浅色", icon: "sunny-outline" },
  { value: "dark", label: "深色", icon: "moon-outline" },
];

function SectionHeader({
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

function DonationCard({
  title,
  icon,
  iconColor,
  source,
  imageClassName,
}: {
  title: string;
  icon: IconName;
  iconColor: string;
  source: number;
  imageClassName: string;
}): JSX.Element {
  return (
    <Card className="items-center p-4">
      <View className="flex-row items-center gap-1.5">
        <Ionicons name={icon} size={20} color={iconColor} />
        <Text className="text-base font-semibold text-surface-foreground">{title}</Text>
      </View>
      <Image source={source} resizeMode="contain" className={imageClassName} />
    </Card>
  );
}

function InfoRow({
  icon,
  label,
  children,
}: {
  icon: IconName;
  label: string;
  children: React.ReactNode;
}): JSX.Element {
  const muted = useCSSVariable("--muted");
  return (
    <View className="flex-row items-center gap-3 py-2">
      <Ionicons name={icon} size={16} color={typeof muted === "string" ? muted : "#8e8e93"} />
      <Text className="w-20 text-sm font-semibold text-foreground">{label}</Text>
      <View className="flex-1">{children}</View>
    </View>
  );
}

export default function SettingsScreen(): JSX.Element {
  const router = useRouter();
  const insets = useSafeAreaInsets();
  const [message, setMessage] = useState<string | null>(null);
  const [currentMode, setCurrentMode] = useState<ThemeMode>("system");
  const foreground = useCSSVariable("--foreground");
  const accent = useCSSVariable("--accent");
  const foregroundColor = typeof foreground === "string" ? foreground : "#000";
  const accentColor = typeof accent === "string" ? accent : "#3b82f6";

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

  const contactAuthor = async (): Promise<void> => {
    setMessage(null);
    try {
      await Linking.openURL(`mailto:${AUTHOR_EMAIL}`);
    } catch {
      await Clipboard.setStringAsync(AUTHOR_EMAIL);
      setMessage("未找到可用的邮件应用，邮箱地址已复制到剪贴板");
    }
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
          <View className="flex-row gap-2">
            {themeModes.map((item) => (
              <Chip
                key={item.value}
                size="md"
                variant={currentMode === item.value ? "primary" : "secondary"}
                color="accent"
                onPress={() => selectMode(item.value)}
              >
                <Chip.Label>{item.label}</Chip.Label>
              </Chip>
            ))}
          </View>
        </View>

        <View className="h-px bg-separator" />

        <View className="items-center gap-1">
          <View className="mb-2 h-16 w-16 items-center justify-center rounded-2xl bg-accent-soft">
            <Ionicons name="videocam-outline" size={30} color={accentColor} />
          </View>
          <Text className="text-2xl font-bold text-foreground">无损视频旋转</Text>
          <Text className="text-base text-muted">主打无损视频旋转，拯救 NIKON 用户</Text>
          <View className="mt-2 rounded-full bg-surface-secondary px-3 py-1">
            <Text className="text-xs font-medium text-muted">V 2.0.0</Text>
          </View>
        </View>

        <View className="gap-1">
          <SectionHeader icon="person-circle-outline" title="关于作者" />
          <InfoRow icon="person-outline" label="软件作者">
            <Text className="text-sm text-foreground">顶天立宇</Text>
          </InfoRow>
          <InfoRow icon="mail-outline" label="联系作者">
            <Button size="sm" variant="tertiary" onPress={() => void contactAuthor()}>
              {AUTHOR_EMAIL}
            </Button>
          </InfoRow>
          {message != null && <Text className="pl-11 text-xs text-success">{message}</Text>}
        </View>

        <View className="gap-3">
          <View className="items-center">
            <SectionHeader icon="cafe-outline" title="赞赏作者" />
            <Text className="mt-1 text-sm text-muted">请作者喝一杯奶茶吧！</Text>
          </View>
          <View className="w-full items-center gap-4">
            <DonationCard
              title="支付宝"
              icon="logo-alipay"
              iconColor="#1677ff"
              source={require("../../assets/images/donate_alipay.jpg")}
              imageClassName="mt-3 h-[300px] w-[200px]"
            />
            <DonationCard
              title="微信支付"
              icon="logo-wechat"
              iconColor="#07c160"
              source={require("../../assets/images/donate_wechat.png")}
              imageClassName="mt-3 h-[272px] w-[200px]"
            />
          </View>
        </View>
      </ScrollView>
    </View>
  );
}
