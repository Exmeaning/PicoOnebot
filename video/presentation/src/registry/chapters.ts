import type { ChapterDef } from "./types";
import MobileRoute from "../chapters/01-mobile-route/MobileRoute";
import { narrations as mobileRouteNarrations } from "../chapters/01-mobile-route/narrations";

export const CHAPTERS: ChapterDef[] = [
  {
    id: "mobile-route",
    title: "手机端的 NapCat 替代思路",
    narrations: mobileRouteNarrations,
    Component: MobileRoute,
  },
];
