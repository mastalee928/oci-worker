<template>
  <div class="upgrade-loader" :class="{ 'upgrade-loader--light': !themeStore.isDark }" aria-label="UPGRADING">
    <div class="upgrade-loader__stars" aria-hidden="true">
      <span class="upgrade-loader__star"></span>
      <span class="upgrade-loader__star"></span>
      <span class="upgrade-loader__star"></span>
      <span class="upgrade-loader__star"></span>
      <span class="upgrade-loader__star"></span>
      <span class="upgrade-loader__star"></span>
      <span class="upgrade-loader__star"></span>
    </div>
    <div class="upgrade-loader__orb" aria-hidden="true"></div>
    <div class="upgrade-loader__word" aria-label="UPGRADING">
      <span v-for="letter in letters" :key="letter.index" class="upgrade-loader__letter">{{ letter.char }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useThemeStore } from '../stores/theme'

const themeStore = useThemeStore()
const letters = 'UPGRADING'.split('').map((char, index) => ({ char, index }))
</script>

<style scoped>
.upgrade-loader {
  --upgrade-loader-orb-bg: #310b1b;
  --upgrade-loader-letter: rgba(255, 255, 255, 0.4);
  --upgrade-loader-star: rgba(255, 255, 255, 0.9);
  position: relative;
  display: grid;
  place-items: center;
  width: min(100%, 520px);
  height: clamp(240px, 44vw, 340px);
  isolation: isolate;
  overflow: hidden;
  user-select: none;
  -webkit-user-select: none;
  -ms-user-select: none;
}

.upgrade-loader::before {
  content: '';
  position: absolute;
  width: clamp(260px, 62vw, 360px);
  height: clamp(260px, 62vw, 360px);
  border-radius: 50%;
  background:
    radial-gradient(circle, rgba(255, 255, 255, 0.055), transparent 48%),
    radial-gradient(circle, rgba(129, 140, 248, 0.12), transparent 62%);
  filter: blur(6px);
  opacity: 0.72;
  animation: upgrade-halo-breathe 5.2s ease-in-out infinite;
  z-index: 0;
}

.upgrade-loader--light::before {
  background:
    radial-gradient(circle, rgba(80, 86, 120, 0.08), transparent 46%),
    radial-gradient(circle, rgba(99, 102, 241, 0.08), transparent 62%);
}

.upgrade-loader__orb {
  position: absolute;
  width: clamp(118px, 27vw, 154px);
  height: clamp(118px, 27vw, 154px);
  border-radius: 50%;
  background: var(--upgrade-loader-orb-bg);
  box-shadow:
    0 0 38px rgba(255, 255, 255, 0.1),
    0 0 92px rgba(116, 92, 150, 0.18);
  z-index: 2;
}

.upgrade-loader--light .upgrade-loader__orb {
  background: rgba(255, 0, 85, 0.12);
  box-shadow:
    0 16px 34px -20px rgba(42, 20, 36, 0.34),
    0 0 60px -10px rgba(58, 45, 72, 0.18);
}

.upgrade-loader__orb::before {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: inherit;
  box-shadow:
    0 8px 22px 0 rgba(255, 255, 255, 0.98) inset,
    0 22px 38px 0 rgba(255, 244, 218, 0.66) inset,
    0 38px 48px 0 rgba(250, 169, 92, 0.2) inset,
    0 88px 54px 0 rgba(96, 0, 42, 0.055) inset;
  animation: upgrade-orb-rotate 4.8s linear infinite;
}

.upgrade-loader--light .upgrade-loader__orb::before {
  box-shadow:
    0 10px 20px 0 rgba(255, 255, 255, 0.98) inset,
    0 20px 30px 0 rgba(255, 255, 255, 0.34) inset,
    0 60px 60px 0 rgba(255, 0, 42, 0.08) inset;
  animation: upgrade-orb-rotate-light 4.8s linear infinite;
}

.upgrade-loader__stars {
  position: absolute;
  inset: 0;
  z-index: 1;
}

.upgrade-loader--light .upgrade-loader__stars {
  transform: translateZ(0);
}

.upgrade-loader__star {
  position: absolute;
  left: 50%;
  top: 50%;
  width: 5px;
  height: 5px;
  margin: -2.5px 0 0 -2.5px;
  border-radius: 50%;
  background: var(--upgrade-loader-star);
  box-shadow: 0 0 12px currentColor;
  filter: blur(0.3px);
  animation: upgrade-star-pulse 2.4s ease-in-out infinite;
}

.upgrade-loader--light .upgrade-loader__star {
  background: rgba(82, 87, 110, 0.72);
  color: rgba(82, 87, 110, 0.28);
  box-shadow: 0 0 10px rgba(82, 87, 110, 0.28);
  filter: blur(0.4px);
  animation-name: upgrade-star-pulse-light;
}

.upgrade-loader__star:nth-child(1) { transform: translate(-122px, -78px) scale(0.72); animation-delay: 0s; }
.upgrade-loader__star:nth-child(2) { transform: translate(118px, -64px) scale(0.92); animation-delay: 0.2s; }
.upgrade-loader__star:nth-child(3) { transform: translate(-132px, 54px) scale(1.32); animation-delay: 0.4s; }
.upgrade-loader__star:nth-child(4) { transform: translate(108px, 74px) scale(0.78); animation-delay: 0.6s; }
.upgrade-loader__star:nth-child(5) { transform: translate(-54px, 112px) scale(1.18); animation-delay: 0.8s; }
.upgrade-loader__star:nth-child(6) { transform: translate(36px, -126px) scale(1.12); animation-delay: 1s; }
.upgrade-loader__star:nth-child(7) { transform: translate(146px, 22px) scale(0.62); animation-delay: 1.2s; }

.upgrade-loader__word {
  position: relative;
  z-index: 3;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: clamp(9px, 2.6vw, 18px);
  color: var(--upgrade-loader-letter);
  font-weight: 800;
  font-size: clamp(28px, 8vw, 54px);
  line-height: 1;
  -webkit-user-drag: none;
}

.upgrade-loader--light .upgrade-loader__word {
  padding-top: 2px;
  transform: translateY(2px);
}

.upgrade-loader--light .upgrade-loader__word::before {
  content: '';
  position: absolute;
  left: -42px;
  right: -42px;
  top: -22px;
  bottom: -22px;
  background: linear-gradient(90deg, transparent, rgba(32, 38, 56, 0.12), transparent);
  opacity: 0.42;
  filter: blur(14px);
  transform: translateX(-38%);
  animation: upgrade-focus-glow-light 2.25s ease-in-out infinite;
  z-index: -1;
}

.upgrade-loader__letter {
  display: inline-block;
  min-width: 0.56em;
  text-align: center;
  opacity: 0.2;
  filter: blur(3px);
  animation: upgrade-letter-anim 1.72s cubic-bezier(0.32, 0.1, 0.24, 1) infinite;
  -webkit-user-drag: none;
}

.upgrade-loader--light .upgrade-loader__letter {
  opacity: 0.4;
  filter: blur(2px);
  transform: translateY(0) scale(1);
  text-shadow: none;
  animation-name: upgrade-letter-anim-light;
  will-change: opacity, filter, transform, text-shadow;
}

.upgrade-loader__letter:nth-child(1) { animation-delay: 0s; }
.upgrade-loader__letter:nth-child(2) { animation-delay: 0.1s; }
.upgrade-loader__letter:nth-child(3) { animation-delay: 0.2s; }
.upgrade-loader__letter:nth-child(4) { animation-delay: 0.3s; }
.upgrade-loader__letter:nth-child(5) { animation-delay: 0.4s; }
.upgrade-loader__letter:nth-child(6) { animation-delay: 0.5s; }
.upgrade-loader__letter:nth-child(7) { animation-delay: 0.6s; }
.upgrade-loader__letter:nth-child(8) { animation-delay: 0.7s; }
.upgrade-loader__letter:nth-child(9) { animation-delay: 0.8s; }

@keyframes upgrade-orb-rotate {
  0% {
    transform: rotate(90deg);
    box-shadow:
      0 8px 22px 0 rgba(255, 255, 255, 0.98) inset,
      0 22px 38px 0 rgba(255, 244, 218, 0.66) inset,
      0 38px 48px 0 rgba(250, 169, 92, 0.2) inset,
      0 88px 54px 0 rgba(96, 0, 42, 0.055) inset;
  }

  50% {
    transform: rotate(270deg);
    box-shadow:
      0 8px 22px 0 rgba(255, 255, 255, 0.98) inset,
      0 22px 18px 0 rgba(255, 224, 164, 0.68) inset,
      0 34px 50px 0 rgba(245, 144, 78, 0.22) inset,
      0 82px 54px 0 rgba(91, 0, 44, 0.065) inset;
  }

  100% {
    transform: rotate(450deg);
    box-shadow:
      0 8px 22px 0 rgba(255, 255, 255, 0.98) inset,
      0 22px 38px 0 rgba(255, 244, 218, 0.66) inset,
      0 38px 48px 0 rgba(250, 169, 92, 0.2) inset,
      0 88px 54px 0 rgba(96, 0, 42, 0.055) inset;
  }
}

@keyframes upgrade-orb-rotate-light {
  0% {
    transform: rotate(90deg);
    box-shadow:
      0 10px 20px 0 rgba(255, 255, 255, 0.98) inset,
      0 20px 30px 0 rgba(255, 255, 255, 0.34) inset,
      0 60px 60px 0 rgba(255, 0, 42, 0.08) inset;
  }

  50% {
    transform: rotate(270deg);
    box-shadow:
      0 10px 20px 0 rgba(255, 255, 255, 0.98) inset,
      0 20px 10px 0 rgba(255, 170, 9, 0.6) inset,
      0 40px 60px 0 rgba(255, 0, 34, 0.12) inset;
  }

  100% {
    transform: rotate(450deg);
    box-shadow:
      0 10px 20px 0 rgba(255, 255, 255, 0.98) inset,
      0 20px 30px 0 rgba(255, 255, 255, 0.34) inset,
      0 60px 60px 0 rgba(255, 0, 42, 0.08) inset;
  }
}

@keyframes upgrade-letter-anim-light {
  0%,
  100% {
    opacity: 0;
    transform: translateY(0) scale(0.94);
    filter: blur(4px);
    text-shadow: none;
  }

  18% {
    opacity: 1;
    transform: scale(1.2) translateY(-1px);
    filter: blur(0);
    text-shadow:
      0 0 2px #fff,
      0 0 6px #000;
  }

  40% {
    opacity: 0.7;
    transform: translateY(0) scale(1.02);
    filter: blur(2px);
    text-shadow: none;
  }

  68% {
    opacity: 0.24;
    transform: translateY(0) scale(0.98);
    filter: blur(3px);
    text-shadow: none;
  }
}

@keyframes upgrade-letter-anim {
  0%,
  100% {
    opacity: 0.14;
    transform: translateY(0) scale(0.94);
    filter: blur(4px);
  }

  18% {
    opacity: 1;
    transform: scale(1.2) translateY(-1px);
    filter: blur(0);
    text-shadow:
      0 0 2px #fff,
      0 0 6px rgba(0, 0, 0, 0.68);
  }

  40% {
    opacity: 0.7;
    transform: translateY(0) scale(1.02);
    filter: blur(2px);
  }

  68% {
    opacity: 0.24;
    transform: translateY(0) scale(0.98);
    filter: blur(3px);
  }
}

@keyframes upgrade-star-pulse {
  0%,
  100% {
    opacity: 0.16;
    filter: blur(2px);
  }

  42% {
    opacity: 0.92;
    filter: blur(0);
  }
}

@keyframes upgrade-star-pulse-light {
  0%,
  100% {
    opacity: 0.14;
    filter: blur(2px);
  }

  42% {
    opacity: 0.78;
    filter: blur(0);
    box-shadow:
      0 0 8px rgba(82, 87, 110, 0.28),
      0 0 18px rgba(82, 87, 110, 0.16);
  }
}

@keyframes upgrade-halo-breathe {
  0%,
  100% {
    transform: scale(0.94);
    opacity: 0.5;
  }

  50% {
    transform: scale(1.05);
    opacity: 0.8;
  }
}

@media (max-width: 640px) {
  .upgrade-loader {
    height: 260px;
  }
}
</style>
