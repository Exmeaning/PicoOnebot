import type { ChapterDef } from "./types";
import MobileRoute from "../chapters/01-mobile-route/MobileRoute";
import { narrations as mobileRouteNarrations } from "../chapters/01-mobile-route/narrations";
import PhoneSetup from "../chapters/02-phone-setup/PhoneSetup";
import { narrations as phoneSetupNarrations } from "../chapters/02-phone-setup/narrations";
import AstrbotSide from "../chapters/03-astrbot-side/AstrbotSide";
import { narrations as astrbotSideNarrations } from "../chapters/03-astrbot-side/narrations";
import ConnectTwoSides from "../chapters/04-connect-two-sides/ConnectTwoSides";
import { narrations as connectTwoSidesNarrations } from "../chapters/04-connect-two-sides/narrations";
import FitAndLimit from "../chapters/05-fit-and-limit/FitAndLimit";
import { narrations as fitAndLimitNarrations } from "../chapters/05-fit-and-limit/narrations";

export const CHAPTERS: ChapterDef[] = [
  {
    id: "mobile-route",
    title: "手机端的 NapCat 替代思路",
    narrations: mobileRouteNarrations,
    Component: MobileRoute,
  },
  {
    id: "phone-setup",
    title: "APK 安装与扫码登录",
    narrations: phoneSetupNarrations,
    Component: PhoneSetup,
  },
  {
    id: "bot-endpoint",
    title: "机器人端只准备一个入口",
    narrations: astrbotSideNarrations,
    Component: AstrbotSide,
  },
  {
    id: "connect-test",
    title: "PicoOnebot 回连与验证",
    narrations: connectTwoSidesNarrations,
    Component: ConnectTwoSides,
  },
  {
    id: "fit-and-limit",
    title: "它适合什么场景",
    narrations: fitAndLimitNarrations,
    Component: FitAndLimit,
  },
];
