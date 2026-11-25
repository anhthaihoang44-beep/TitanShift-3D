import AVFoundation
import Foundation
import UIKit

final class SoundHapticsService {
    var soundEnabled: Bool = true
    var hapticsEnabled: Bool = true

    private let lightImpact = UIImpactFeedbackGenerator(style: .light)
    private let mediumImpact = UIImpactFeedbackGenerator(style: .medium)
    private let heavyImpact = UIImpactFeedbackGenerator(style: .heavy)
    private let rigidImpact = UIImpactFeedbackGenerator(style: .rigid)
    private let notifyGenerator = UINotificationFeedbackGenerator()

    private var cachedWavBuffers: [GameEvent: Data] = [:]
    private var activePlayers: [AVAudioPlayer] = []
    private let audioQueue = DispatchQueue(label: "com.titanshift.ios.audio", qos: .userInteractive)

    init() {
        try? AVAudioSession.sharedInstance().setCategory(.ambient, mode: .default, options: [.mixWithOthers])
        try? AVAudioSession.sharedInstance().setActive(true)
        lightImpact.prepare()
        heavyImpact.prepare()

        audioQueue.async { [weak self] in
            self?.prebuildAllSounds()
        }
    }

    func trigger(_ event: GameEvent) {
        if hapticsEnabled {
            DispatchQueue.main.async { [weak self] in
                self?.fireHaptic(event)
            }
        }
        if soundEnabled {
            audioQueue.async { [weak self] in
                self?.playWav(for: event)
            }
        }
    }

    private func fireHaptic(_ event: GameEvent) {
        switch event {
        case .scaleZoneTick, .coinPickup:
            lightImpact.impactOccurred(intensity: 0.55)
        case .slipUnder, .glassSafe, .giantLeap:
            mediumImpact.impactOccurred(intensity: 0.8)
        case .perfectSync, .shieldGain:
            rigidImpact.impactOccurred(intensity: 0.9)
        case .wallSmash, .rivalCrush, .multiplierBreak, .bossClashHit:
            heavyImpact.impactOccurred(intensity: 1.0)
        case .overdriveActivated, .stageVictory:
            notifyGenerator.notificationOccurred(.success)
        case .glassShatter, .shieldBlock:
            notifyGenerator.notificationOccurred(.warning)
        case .playerDefeat:
            notifyGenerator.notificationOccurred(.error)
        }
    }

    private func playWav(for event: GameEvent) {
        guard let data = cachedWavBuffers[event] else { return }
        if let player = try? AVAudioPlayer(data: data, fileTypeHint: AVFileType.wav.rawValue) {
            activePlayers.removeAll { !$0.isPlaying }
            if activePlayers.count < 8 {
                player.prepareToPlay()
                player.play()
                activePlayers.append(player)
            }
        }
    }

    private func prebuildAllSounds() {
        let sr = 22050
        cachedWavBuffers[.scaleZoneTick] = encodeWav(samples: synthSweep(sr: sr, dur: 0.035, f0: 520, f1: 680, noise: 0, vol: 0.16), sampleRate: sr)
        cachedWavBuffers[.coinPickup] = encodeWav(samples: synthArpeggio(sr: sr, notes: [880, 1320], stepDur: 0.045, vol: 0.25), sampleRate: sr)
        cachedWavBuffers[.shieldGain] = encodeWav(samples: synthArpeggio(sr: sr, notes: [587.3, 880, 1174.6], stepDur: 0.055, vol: 0.28), sampleRate: sr)
        cachedWavBuffers[.slipUnder] = encodeWav(samples: synthSweep(sr: sr, dur: 0.12, f0: 420, f1: 940, noise: 0.08, vol: 0.28), sampleRate: sr)
        cachedWavBuffers[.glassSafe] = cachedWavBuffers[.slipUnder]
        cachedWavBuffers[.giantLeap] = encodeWav(samples: synthSweep(sr: sr, dur: 0.16, f0: 220, f1: 660, noise: 0.05, vol: 0.32), sampleRate: sr)
        cachedWavBuffers[.perfectSync] = encodeWav(samples: synthArpeggio(sr: sr, notes: [523.25, 659.25, 783.99, 1046.5], stepDur: 0.055, vol: 0.32), sampleRate: sr)
        cachedWavBuffers[.wallSmash] = encodeWav(samples: synthSweep(sr: sr, dur: 0.18, f0: 165, f1: 58, noise: 0.55, vol: 0.42), sampleRate: sr)
        cachedWavBuffers[.rivalCrush] = cachedWavBuffers[.wallSmash]
        cachedWavBuffers[.multiplierBreak] = cachedWavBuffers[.wallSmash]
        cachedWavBuffers[.bossClashHit] = encodeWav(samples: synthSweep(sr: sr, dur: 0.11, f0: 240, f1: 110, noise: 0.38, vol: 0.36), sampleRate: sr)
        cachedWavBuffers[.glassShatter] = encodeWav(samples: synthSweep(sr: sr, dur: 0.22, f0: 1400, f1: 380, noise: 0.72, vol: 0.35), sampleRate: sr)
        cachedWavBuffers[.shieldBlock] = cachedWavBuffers[.glassShatter]
        cachedWavBuffers[.overdriveActivated] = encodeWav(samples: synthArpeggio(sr: sr, notes: [440, 554.37, 659.25, 880, 1108.7], stepDur: 0.055, vol: 0.36), sampleRate: sr)
        cachedWavBuffers[.stageVictory] = encodeWav(samples: synthArpeggio(sr: sr, notes: [523.25, 659.25, 783.99, 1046.5, 1318.5], stepDur: 0.075, vol: 0.38), sampleRate: sr)
        cachedWavBuffers[.playerDefeat] = encodeWav(samples: synthArpeggio(sr: sr, notes: [392, 349.23, 311.13, 261.63], stepDur: 0.09, vol: 0.35), sampleRate: sr)
    }

    private func synthSweep(sr: Int, dur: Float, f0: Float, f1: Float, noise: Float, vol: Float) -> [Int16] {
        let count = max(64, Int(Float(sr) * dur))
        var out = [Int16](repeating: 0, count: count)
        var phase: Double = 0
        var rng = SeededGenerator(seed: 98765)
        for i in 0..<count {
            let t = Float(i) / Float(count)
            let freq = f0 + (f1 - f0) * t
            phase += 2.0 * Double.pi * Double(freq) / Double(sr)
            let env = exp(-3.5 * t)
            let tone = Float(sin(phase) * 0.75 + sin(phase * 2.0) * 0.25)
            let n = rng.nextFloat() * 2.0 - 1.0
            let sample = (tone * (1.0 - noise) + n * noise) * env * vol
            let clamped = min(max(sample, -1.0), 1.0)
            out[i] = Int16(clamped * Float(Int16.max))
        }
        return out
    }

    private func synthArpeggio(sr: Int, notes: [Float], stepDur: Float, vol: Float) -> [Int16] {
        let stepCount = max(64, Int(Float(sr) * stepDur))
        var out = [Int16](repeating: 0, count: stepCount * notes.count)
        var phase: Double = 0
        for (nIdx, freq) in notes.enumerated() {
            for i in 0..<stepCount {
                let localT = Float(i) / Float(stepCount)
                phase += 2.0 * Double.pi * Double(freq) / Double(sr)
                let env = 1.0 - localT * 0.65
                let s = Float(sin(phase) * 0.78 + sin(phase * 1.5) * 0.22) * env * vol
                out[nIdx * stepCount + i] = Int16(min(max(s, -1.0), 1.0) * Float(Int16.max))
            }
        }
        return out
    }

    private func encodeWav(samples: [Int16], sampleRate: Int) -> Data {
        let dataByteCount = samples.count * 2
        let riffChunkSize = UInt32(36 + dataByteCount)
        var data = Data(capacity: 44 + dataByteCount)

        data.append(contentsOf: "RIFF".utf8)
        data.append(littleEndianBytes(riffChunkSize))
        data.append(contentsOf: "WAVEfmt ".utf8)
        data.append(littleEndianBytes(UInt32(16))) // PCM chunk size
        data.append(littleEndianBytes(UInt16(1)))  // AudioFormat = 1 (PCM)
        data.append(littleEndianBytes(UInt16(1)))  // Channels = 1 (Mono)
        data.append(littleEndianBytes(UInt32(sampleRate)))
        data.append(littleEndianBytes(UInt32(sampleRate * 2))) // ByteRate
        data.append(littleEndianBytes(UInt16(2)))  // BlockAlign
        data.append(littleEndianBytes(UInt16(16))) // BitsPerSample
        data.append(contentsOf: "data".utf8)
        data.append(littleEndianBytes(UInt32(dataByteCount)))

        samples.withUnsafeBytes { rawBuf in
            if let base = rawBuf.baseAddress {
                data.append(base.assumingMemoryBound(to: UInt8.self), count: dataByteCount)
            }
        }
        return data
    }

    private func littleEndianBytes<T: FixedWidthInteger>(_ value: T) -> [UInt8] {
        withUnsafeBytes(of: value.littleEndian) { Array($0) }
    }
}
