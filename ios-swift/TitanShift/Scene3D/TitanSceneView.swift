import QuartzCore
import SceneKit
import SwiftUI
import UIKit

struct TitanSceneView: UIViewRepresentable {
    @ObservedObject var viewModel: GameViewModel

    func makeCoordinator() -> SceneCoordinator {
        SceneCoordinator(viewModel: viewModel)
    }

    func makeUIView(context: Context) -> SCNView {
        let scnView = SCNView(frame: .zero)
        scnView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        scnView.contentMode = .scaleToFill
        scnView.scene = context.coordinator.scene
        scnView.pointOfView = context.coordinator.cameraNode
        scnView.antialiasingMode = .multisampling4X
        scnView.preferredFramesPerSecond = 60
        scnView.rendersContinuously = true
        scnView.backgroundColor = UIColor(red: 0.05, green: 0.09, blue: 0.20, alpha: 1.0)
        context.coordinator.startDisplayLink()
        return scnView
    }

    func updateUIView(_ uiView: SCNView, context: Context) {
        context.coordinator.viewModel = viewModel
    }

    static func dismantleUIView(_ uiView: SCNView, coordinator: SceneCoordinator) {
        coordinator.stopDisplayLink()
    }
}

@MainActor
final class SceneCoordinator: NSObject {
    var viewModel: GameViewModel
    let scene = SCNScene()
    let cameraNode = SCNNode()

    private var displayLink: CADisplayLink?
    private var lastTimestamp: CFTimeInterval = 0
    private var builtStageRevision: Int = -1
    private var currentSkinId: String = ""
    private var currentCrestStyle: CrestStyle? = nil

    // Persistent Root Containers
    private let stageWorldNode = SCNNode()
    private let heroRootNode = SCNNode()
    private let debrisContainerNode = SCNNode()

    // Hero Articulated Parts
    private let heroBodyPivot = SCNNode()
    private let shadowNode = SCNNode()
    private let leftLegPivot = SCNNode()
    private let rightLegPivot = SCNNode()
    private let leftArmPivot = SCNNode()
    private let rightArmPivot = SCNNode()
    private let torsoNode = SCNNode()
    private let leftShoulderNode = SCNNode()
    private let rightShoulderNode = SCNNode()
    private let chestCoreFront = SCNNode()
    private let chestCoreBack = SCNNode()
    private let headNode = SCNNode()
    private let visorNode = SCNNode()
    private let crestHolderNode = SCNNode()
    private let shieldBubbleNode = SCNNode()

    //Hero Dynamic Materials
    private let primaryMat = SCNMaterial()
    private let darkLimbMat = SCNMaterial()
    private let trimMat = SCNMaterial()
    private let coreMat = SCNMaterial()

    // Indexed stage nodes for fast O(1) frame updates
    private var obstacleNodes: [Int: SCNNode] = [:]
    private var syncArchMaterials: [Int: SCNMaterial] = [:]
    private var pistonBeamNodes: [Int: SCNNode] = [:]
    private var collectibleNodes: [Int: SCNNode] = [:]
    private var multiplierWallNodes: [Int: SCNNode] = [:]
    private var bossRootNode: SCNNode?
    private var bossLeftArm: SCNNode?
    private var bossRightArm: SCNNode?
    private var clashBeamNode: SCNNode?
    private var debrisPoolNodes: [Int: SCNNode] = [:]

    init(viewModel: GameViewModel) {
        self.viewModel = viewModel
        super.init()
        setupSceneLightingAndCamera()
        buildArticulatedHero()
        scene.rootNode.addChildNode(stageWorldNode)
        scene.rootNode.addChildNode(heroRootNode)
        scene.rootNode.addChildNode(debrisContainerNode)
    }

    func startDisplayLink() {
        stopDisplayLink()
        lastTimestamp = 0
        let link = CADisplayLink(target: self, selector: #selector(onFrame(_:)))
        link.add(to: .main, forMode: .common)
        displayLink = link
    }

    func stopDisplayLink() {
        displayLink?.invalidate()
        displayLink = nil
    }

    @objc private func onFrame(_ link: CADisplayLink) {
        let dt: Float
        if lastTimestamp == 0 {
            dt = 1.0 / 60.0
        } else {
            dt = Float(min(max(link.timestamp - lastTimestamp, 0.001), 0.05))
        }
        lastTimestamp = link.timestamp

        viewModel.step(rawDt: dt)
        syncScene(with: viewModel.snapshot)
    }

    private func setupSceneLightingAndCamera() {
        scene.fogStartDistance = 55
        scene.fogEndDistance = 155
        scene.fogColor = UIColor(red: 0.06, green: 0.10, blue: 0.24, alpha: 1.0)

        let camera = SCNCamera()
        camera.fieldOfView = 54
        camera.zNear = 0.5
        camera.zFar = 200
        cameraNode.camera = camera
        scene.rootNode.addChildNode(cameraNode)

        let sunNode = SCNNode()
        let sunLight = SCNLight()
        sunLight.type = .directional
        sunLight.intensity = 1150
        sunLight.color = UIColor(white: 1.0, alpha: 1.0)
        sunNode.light = sunLight
        sunNode.eulerAngles = SCNVector3(-Float.pi * 0.34, Float.pi * 0.20, 0)
        scene.rootNode.addChildNode(sunNode)

        let ambientNode = SCNNode()
        let ambient = SCNLight()
        ambient.type = .ambient
        ambient.intensity = 460
        ambient.color = UIColor(red: 0.65, green: 0.78, blue: 1.0, alpha: 1.0)
        ambientNode.light = ambient
        scene.rootNode.addChildNode(ambientNode)
    }

    private func buildArticulatedHero() {
        primaryMat.lightingModel = .blinn
        darkLimbMat.lightingModel = .blinn
        trimMat.lightingModel = .blinn
        coreMat.lightingModel = .constant

        // Ground shadow
        let shadowCyl = SCNCylinder(radius: 0.52, height: 0.02)
        let shadowMat = SCNMaterial()
        shadowMat.lightingModel = .constant
        shadowMat.diffuse.contents = UIColor(white: 0.02, alpha: 0.50)
        shadowCyl.materials = [shadowMat]
        shadowNode.geometry = shadowCyl
        shadowNode.position = SCNVector3(0, 0.02, 0)
        heroRootNode.addChildNode(shadowNode)
        heroRootNode.addChildNode(heroBodyPivot)

        // Legs
        let legGeo = SCNBox(width: 0.26, height: 0.86, length: 0.28, chamferRadius: 0.05)
        legGeo.materials = [darkLimbMat]
        let leftLegMesh = SCNNode(geometry: legGeo)
        leftLegMesh.position = SCNVector3(0, -0.40, 0)
        leftLegPivot.position = SCNVector3(-0.22, 0.86, 0)
        leftLegPivot.addChildNode(leftLegMesh)
        heroBodyPivot.addChildNode(leftLegPivot)

        let rightLegMesh = SCNNode(geometry: legGeo)
        rightLegMesh.position = SCNVector3(0, -0.40, 0)
        rightLegPivot.position = SCNVector3(0.22, 0.86, 0)
        rightLegPivot.addChildNode(rightLegMesh)
        heroBodyPivot.addChildNode(rightLegPivot)

        // Torso & Pauldrons
        let torsoGeo = SCNBox(width: 0.72, height: 0.78, length: 0.42, chamferRadius: 0.08)
        torsoGeo.materials = [primaryMat]
        torsoNode.geometry = torsoGeo
        torsoNode.position = SCNVector3(0, 1.18, 0)
        heroBodyPivot.addChildNode(torsoNode)

        let pauldronGeo = SCNBox(width: 0.28, height: 0.26, length: 0.46, chamferRadius: 0.06)
        pauldronGeo.materials = [trimMat]
        leftShoulderNode.geometry = pauldronGeo
        leftShoulderNode.position = SCNVector3(-0.46, 1.46, 0)
        heroBodyPivot.addChildNode(leftShoulderNode)

        rightShoulderNode.geometry = pauldronGeo
        rightShoulderNode.position = SCNVector3(0.46, 1.46, 0)
        heroBodyPivot.addChildNode(rightShoulderNode)

        // Morph Core Front & Back
        let coreGeo = SCNBox(width: 0.30, height: 0.32, length: 0.08, chamferRadius: 0.03)
        coreGeo.materials = [coreMat]
        chestCoreFront.geometry = coreGeo
        chestCoreFront.position = SCNVector3(0, 1.22, 0.23)
        heroBodyPivot.addChildNode(chestCoreFront)

        chestCoreBack.geometry = coreGeo
        chestCoreBack.position = SCNVector3(0, 1.22, -0.23)
        heroBodyPivot.addChildNode(chestCoreBack)

        // Arms
        let armGeo = SCNBox(width: 0.22, height: 0.72, length: 0.24, chamferRadius: 0.05)
        armGeo.materials = [primaryMat]
        let leftArmMesh = SCNNode(geometry: armGeo)
        leftArmMesh.position = SCNVector3(0, -0.32, 0)
        leftArmPivot.position = SCNVector3(-0.52, 1.42, 0)
        leftArmPivot.addChildNode(leftArmMesh)
        heroBodyPivot.addChildNode(leftArmPivot)

        let rightArmMesh = SCNNode(geometry: armGeo)
        rightArmMesh.position = SCNVector3(0, -0.32, 0)
        rightArmPivot.position = SCNVector3(0.52, 1.42, 0)
        rightArmPivot.addChildNode(rightArmMesh)
        heroBodyPivot.addChildNode(rightArmPivot)

        // Head + Visor + Crest
        let headGeo = SCNBox(width: 0.46, height: 0.44, length: 0.46, chamferRadius: 0.09)
        headGeo.materials = [primaryMat]
        headNode.geometry = headGeo
        headNode.position = SCNVector3(0, 1.80, 0)
        heroBodyPivot.addChildNode(headNode)

        let visorGeo = SCNBox(width: 0.38, height: 0.16, length: 0.08, chamferRadius: 0.03)
        visorGeo.materials = [coreMat]
        visorNode.geometry = visorGeo
        visorNode.position = SCNVector3(0, 1.83, 0.24)
        heroBodyPivot.addChildNode(visorNode)

        crestHolderNode.position = SCNVector3(0, 2.04, 0)
        heroBodyPivot.addChildNode(crestHolderNode)

        // Shield Bubble
        let shieldSphere = SCNSphere(radius: 1.15)
        let shieldMat = SCNMaterial()
        shieldMat.lightingModel = .constant
        shieldMat.diffuse.contents = UIColor(red: 0.0, green: 0.88, blue: 1.0, alpha: 0.24)
        shieldMat.isDoubleSided = true
        shieldSphere.materials = [shieldMat]
        shieldBubbleNode.geometry = shieldSphere
        shieldBubbleNode.position = SCNVector3(0, 1.15, 0)
        heroBodyPivot.addChildNode(shieldBubbleNode)
    }

    private func rebuildCrest(style: CrestStyle) {
        crestHolderNode.childNodes.forEach { $0.removeFromParentNode() }
        switch style {
        case .fin:
            let fin = SCNBox(width: 0.10, height: 0.26, length: 0.42, chamferRadius: 0.03)
            fin.materials = [coreMat]
            let n = SCNNode(geometry: fin)
            n.position = SCNVector3(0, 0.10, -0.04)
            crestHolderNode.addChildNode(n)
        case .horns:
            let cone = SCNCone(topRadius: 0.01, bottomRadius: 0.09, height: 0.42)
            cone.materials = [trimMat]
            let left = SCNNode(geometry: cone)
            left.position = SCNVector3(-0.22, 0.12, 0)
            left.eulerAngles = SCNVector3(0, 0, 0.35)
            let right = SCNNode(geometry: cone)
            right.position = SCNVector3(0.22, 0.12, 0)
            right.eulerAngles = SCNVector3(0, 0, -0.35)
            crestHolderNode.addChildNode(left)
            crestHolderNode.addChildNode(right)
        case .visor:
            let band = SCNBox(width: 0.54, height: 0.16, length: 0.52, chamferRadius: 0.04)
            band.materials = [coreMat]
            let n = SCNNode(geometry: band)
            n.position = SCNVector3(0, -0.18, 0)
            crestHolderNode.addChildNode(n)
        case .halo:
            let torus = SCNTorus(ringRadius: 0.32, pipeRadius: 0.045)
            torus.materials = [coreMat]
            let n = SCNNode(geometry: torus)
            n.position = SCNVector3(0, 0.22, 0)
            crestHolderNode.addChildNode(n)
        case .crown:
            let center = SCNPyramid(width: 0.18, height: 0.38, length: 0.18)
            center.materials = [coreMat]
            let side = SCNPyramid(width: 0.14, height: 0.28, length: 0.14)
            side.materials = [trimMat]
            let cNode = SCNNode(geometry: center)
            let lNode = SCNNode(geometry: side)
            lNode.position = SCNVector3(-0.18, 0, 0)
            let rNode = SCNNode(geometry: side)
            rNode.position = SCNVector3(0.18, 0, 0)
            crestHolderNode.addChildNode(cNode)
            crestHolderNode.addChildNode(lNode)
            crestHolderNode.addChildNode(rNode)
        case .sovereign:
            let center = SCNPyramid(width: 0.22, height: 0.48, length: 0.22)
            center.materials = [coreMat]
            let horn = SCNCone(topRadius: 0.01, bottomRadius: 0.09, height: 0.42)
            horn.materials = [trimMat]
            let cNode = SCNNode(geometry: center)
            let lNode = SCNNode(geometry: horn)
            lNode.position = SCNVector3(-0.26, 0.12, 0)
            lNode.eulerAngles = SCNVector3(0, 0, 0.40)
            let rNode = SCNNode(geometry: horn)
            rNode.position = SCNVector3(0.26, 0.12, 0)
            rNode.eulerAngles = SCNVector3(0, 0, -0.40)
            crestHolderNode.addChildNode(cNode)
            crestHolderNode.addChildNode(lNode)
            crestHolderNode.addChildNode(rNode)
        }
    }

    private func rebuildStageGeometry(for snap: GameSnapshot) {
        stageWorldNode.childNodes.forEach { $0.removeFromParentNode() }
        obstacleNodes.removeAll()
        syncArchMaterials.removeAll()
        pistonBeamNodes.removeAll()
        collectibleNodes.removeAll()
        multiplierWallNodes.removeAll()
        bossRootNode = nil
        clashBeamNode = nil

        let totalEndZ: Float
        if snap.isBossStage {
            totalEndZ = (snap.boss?.bossZ ?? snap.trackLength) + 18.0
        } else {
            totalEndZ = (snap.multiplierWalls.last?.z ?? snap.trackLength) + 22.0
        }

        // 1. Cyber Skyline Pillars
        let maxIdx = Int(totalEndZ / 24.0) + 3
        for idx in -1...maxIdx {
            let z = Float(idx) * 24.0
            let hL = Float(18 + abs(idx * 37) % 14)
            let hR = Float(16 + abs(idx * 53) % 16)
            stageWorldNode.addChildNode(makeBoxNode(w: 3.2, h: hL, l: 3.2, x: -14.5, y: hL * 0.5 - 14, z: z, color: UIColor(red: 0.09, green: 0.15, blue: 0.32, alpha: 1)))
            stageWorldNode.addChildNode(makeBoxNode(w: 0.18, h: hL * 0.85, l: 0.35, x: -12.8, y: hL * 0.5 - 14, z: z, color: .cyan, emissive: true))
            stageWorldNode.addChildNode(makeBoxNode(w: 3.2, h: hR, l: 3.2, x: 14.5, y: hR * 0.5 - 14, z: z + 10, color: UIColor(red: 0.10, green: 0.14, blue: 0.30, alpha: 1)))
            stageWorldNode.addChildNode(makeBoxNode(w: 0.18, h: hR * 0.85, l: 0.35, x: 12.8, y: hR * 0.5 - 14, z: z + 10, color: .systemPink, emissive: true))
        }

        // 2. Carve Skyway Deck around Chasms & Glass Bridges
        let carved = snap.obstacles.filter { $0.type == .abyssChasmGap || $0.type == .fragileGlassBridge }
            .sorted { $0.zStart < $1.zStart }

        var cursorZ: Float = -10.0
        for obs in carved {
            if obs.zStart > cursorZ + 0.2 {
                addTrackSegment(z0: cursorZ, z1: obs.zStart)
            }
            cursorZ = max(cursorZ, obs.zEnd)
        }
        if totalEndZ > cursorZ + 0.2 {
            addTrackSegment(z0: cursorZ, z1: totalEndZ)
        }

        // 3. Finish Portal
        addFinishPortal(z: snap.trackLength, isBoss: snap.isBossStage)

        // 4. Collectibles
        for orb in snap.collectibles {
            let n: SCNNode
            if orb.isShieldCrystal {
                let pyr = SCNPyramid(width: 0.65, height: 0.88, length: 0.65)
                let mat = SCNMaterial()
                mat.lightingModel = .constant
                mat.diffuse.contents = UIColor.cyan
                pyr.materials = [mat]
                n = SCNNode(geometry: pyr)
            } else {
                let cyl = SCNCylinder(radius: 0.35, height: 0.14)
                let mat = SCNMaterial()
                mat.lightingModel = .blinn
                mat.diffuse.contents = UIColor(red: 1.0, green: 0.82, blue: 0.12, alpha: 1.0)
                mat.emission.contents = UIColor(red: 0.45, green: 0.32, blue: 0.0, alpha: 1.0)
                cyl.materials = [mat]
                let holder = SCNNode()
                let disc = SCNNode(geometry: cyl)
                disc.eulerAngles = SCNVector3(Float.pi * 0.5, 0, 0)
                holder.addChildNode(disc)
                n = holder
            }
            n.position = SCNVector3(orb.x, orb.y, orb.z)
            stageWorldNode.addChildNode(n)
            collectibleNodes[orb.id] = n
        }

        // 5. Obstacles
        for obs in snap.obstacles {
            let node = createObstacleNode(obs)
            stageWorldNode.addChildNode(node)
            obstacleNodes[obs.id] = node
        }

        // 6. Multiplier Gauntlet Walls
        for wall in snap.multiplierWalls {
            let container = SCNNode()
            container.position = SCNVector3(0, 0, wall.z)
            let slab = makeBoxNode(
                w: 4.9, h: 4.0, l: 0.65,
                x: 0, y: 2.0, z: 0,
                color: wall.color.uiColor.withAlphaComponent(0.84)
            )
            let topTrim = makeBoxNode(w: 5.1, h: 0.22, l: 0.75, x: 0, y: 4.05, z: 0, color: .white, emissive: true)
            container.addChildNode(slab)
            container.addChildNode(topTrim)
            stageWorldNode.addChildNode(container)
            multiplierWallNodes[wall.index] = container
        }

        // 7. Boss Node if Boss Stage
        if let boss = snap.boss {
            let bNode = buildBossNode(scale: boss.bossScale)
            bNode.position = SCNVector3(0, 0, boss.bossZ)
            stageWorldNode.addChildNode(bNode)
            bossRootNode = bNode

            let beam = makeBoxNode(w: 0.75, h: 0.75, l: 6.5, x: 0, y: 2.0, z: boss.bossZ - 3.25, color: .systemYellow, emissive: true)
            beam.isHidden = true
            stageWorldNode.addChildNode(beam)
            clashBeamNode = beam
        }
    }

    private func addTrackSegment(z0: Float, z1: Float) {
        let len = z1 - z0
        let midZ = (z0 + z1) * 0.5
        stageWorldNode.addChildNode(makeBoxNode(w: 5.4, h: 0.7, l: len, x: 0, y: -0.35, z: midZ, color: UIColor(red: 0.14, green: 0.19, blue: 0.30, alpha: 1)))
        stageWorldNode.addChildNode(makeBoxNode(w: 0.38, h: 0.03, l: len, x: 0, y: 0.01, z: midZ, color: UIColor(red: 0.20, green: 0.42, blue: 0.68, alpha: 1), emissive: true))
        stageWorldNode.addChildNode(makeBoxNode(w: 0.22, h: 0.28, l: len, x: -2.75, y: 0.14, z: midZ, color: .cyan, emissive: true))
        stageWorldNode.addChildNode(makeBoxNode(w: 0.22, h: 0.28, l: len, x: 2.75, y: 0.14, z: midZ, color: .cyan, emissive: true))
    }

    private func addFinishPortal(z: Float, isBoss: Bool) {
        let accent = isBoss ? UIColor.systemPink : UIColor.systemGreen
        stageWorldNode.addChildNode(makeBoxNode(w: 0.45, h: 5.6, l: 0.45, x: -2.6, y: 2.8, z: z, color: accent, emissive: true))
        stageWorldNode.addChildNode(makeBoxNode(w: 0.45, h: 5.6, l: 0.45, x: 2.6, y: 2.8, z: z, color: accent, emissive: true))
        stageWorldNode.addChildNode(makeBoxNode(w: 5.7, h: 0.65, l: 0.5, x: 0, y: 5.5, z: z, color: accent, emissive: true))
    }

    private func createObstacleNode(_ obs: ObstacleInstance) -> SCNNode {
        let root = SCNNode()
        let midZ = obs.zStart + obs.zLength * 0.5

        switch obs.type {
        case .lowLaserGate:
            root.position = SCNVector3(0, 0, midZ)
            root.addChildNode(makeBoxNode(w: 0.52, h: 4.6, l: 0.65, x: -2.45, y: 2.3, z: 0, color: .darkGray))
            root.addChildNode(makeBoxNode(w: 0.52, h: 4.6, l: 0.65, x: 2.45, y: 2.3, z: 0, color: .darkGray))
            root.addChildNode(makeBoxNode(w: 4.8, h: 2.2, l: 0.62, x: 0, y: 2.83, z: 0, color: UIColor(red: 0.85, green: 0.18, blue: 0.24, alpha: 1)))
            root.addChildNode(makeBoxNode(w: 4.85, h: 0.22, l: 0.72, x: 0, y: 1.60, z: 0, color: .systemRed, emissive: true))

        case .movingPistonGate:
            root.position = SCNVector3(0, 0, midZ)
            root.addChildNode(makeBoxNode(w: 0.52, h: 4.6, l: 0.65, x: -2.45, y: 2.3, z: 0, color: .darkGray))
            root.addChildNode(makeBoxNode(w: 0.52, h: 4.6, l: 0.65, x: 2.45, y: 2.3, z: 0, color: .darkGray))
            let beamHolder = SCNNode()
            beamHolder.addChildNode(makeBoxNode(w: 4.8, h: 2.0, l: 0.62, x: 0, y: 2.75, z: 0, color: .systemOrange))
            beamHolder.addChildNode(makeBoxNode(w: 4.85, h: 0.22, l: 0.72, x: 0, y: 1.60, z: 0, color: .yellow, emissive: true))
            root.addChildNode(beamHolder)
            pistonBeamNodes[obs.id] = beamHolder

        case .heavyBrickWall:
            root.position = SCNVector3(0, 0, midZ)
            for row in 0..<4 {
                let y: Float = 0.45 + Float(row) * 0.88
                let stagger: Float = (row % 2 == 0) ? -0.25 : 0.25
                for col in -1...1 {
                    let x = min(max(Float(col) * 1.42 + stagger, -1.9), 1.9)
                    let shade: CGFloat = ((row + col) % 2 == 0) ? 0.94 : 0.80
                    root.addChildNode(
                        makeBoxNode(
                            w: 1.32, h: 0.80, l: 1.15,
                            x: x, y: y, z: 0,
                            color: UIColor(red: 0.92 * shade, green: 0.42 * shade, blue: 0.18 * shade, alpha: 1.0)
                        )
                    )
                }
            }

        case .fragileGlassBridge:
            root.position = SCNVector3(0, 0, midZ)
            root.addChildNode(makeBoxNode(w: 0.18, h: 0.16, l: obs.zLength, x: -2.2, y: 0.02, z: 0, color: .cyan, emissive: true))
            root.addChildNode(makeBoxNode(w: 0.18, h: 0.16, l: obs.zLength, x: 2.2, y: 0.02, z: 0, color: .cyan, emissive: true))
            root.addChildNode(
                makeBoxNode(
                    w: 4.25, h: 0.14, l: obs.zLength,
                    x: 0, y: -0.06, z: 0,
                    color: UIColor(red: 0.45, green: 0.90, blue: 1.0, alpha: 0.48),
                    emissive: true
                )
            )

        case .abyssChasmGap:
            root.addChildNode(makeBoxNode(w: 5.2, h: 0.18, l: 0.75, x: 0, y: 0.06, z: obs.zStart - 0.35, color: .systemOrange, emissive: true))
            root.addChildNode(makeBoxNode(w: 5.2, h: 0.18, l: 0.75, x: 0, y: 0.06, z: obs.zEnd + 0.35, color: .systemOrange, emissive: true))

        case .precisionSyncArch:
            root.position = SCNVector3(0, 0, midZ)
            let archMat = SCNMaterial()
            archMat.lightingModel = .constant
            archMat.diffuse.contents = UIColor.systemGreen
            syncArchMaterials[obs.id] = archMat

            let targetH = obs.maxSafeScale * 1.95
            let leftP = SCNNode(geometry: SCNBox(width: 0.38, height: CGFloat(targetH), length: 0.45, chamferRadius: 0.04))
            leftP.geometry?.materials = [archMat]
            leftP.position = SCNVector3(-2.1, targetH * 0.5, 0)

            let rightP = SCNNode(geometry: SCNBox(width: 0.38, height: CGFloat(targetH), length: 0.45, chamferRadius: 0.04))
            rightP.geometry?.materials = [archMat]
            rightP.position = SCNVector3(2.1, targetH * 0.5, 0)

            let topB = SCNNode(geometry: SCNBox(width: 4.58, height: 0.38, length: 0.48, chamferRadius: 0.04))
            topB.geometry?.materials = [archMat]
            topB.position = SCNVector3(0, targetH, 0)

            root.addChildNode(leftP)
            root.addChildNode(rightP)
            root.addChildNode(topB)

        case .rivalEnforcer:
            root.position = SCNVector3(0, 0, midZ)
            root.addChildNode(makeBoxNode(w: 1.35, h: 1.35, l: 0.95, x: 0, y: 1.25, z: 0, color: .systemRed))
            root.addChildNode(makeBoxNode(w: 0.82, h: 0.68, l: 0.78, x: 0, y: 2.25, z: 0, color: .darkGray))
            root.addChildNode(makeBoxNode(w: 0.68, h: 0.22, l: 0.12, x: 0, y: 2.28, z: -0.38, color: .yellow, emissive: true))
            root.addChildNode(makeBoxNode(w: 0.42, h: 0.84, l: 0.52, x: -0.42, y: 0.42, z: 0, color: .black))
            root.addChildNode(makeBoxNode(w: 0.42, h: 0.84, l: 0.52, x: 0.42, y: 0.42, z: 0, color: .black))
        }

        return root
    }

    private func buildBossNode(scale s: Float) -> SCNNode {
        let root = SCNNode()
        root.addChildNode(makeBoxNode(w: 1.45 * s, h: 1.35 * s, l: 0.95 * s, x: 0, y: 1.1 * s, z: 0, color: UIColor(red: 0.24, green: 0.08, blue: 0.18, alpha: 1)))
        root.addChildNode(makeBoxNode(w: 0.58 * s, h: 0.58 * s, l: 0.16 * s, x: 0, y: 1.18 * s, z: -0.5 * s, color: .systemOrange, emissive: true))
        root.addChildNode(makeBoxNode(w: 0.78 * s, h: 0.68 * s, l: 0.75 * s, x: 0, y: 2.05 * s, z: 0, color: .systemRed))

        let hornGeo = SCNCone(topRadius: 0.02, bottomRadius: CGFloat(0.16 * s), height: CGFloat(0.68 * s))
        let hornMat = SCNMaterial()
        hornMat.lightingModel = .constant
        hornMat.diffuse.contents = UIColor.systemYellow
        hornGeo.materials = [hornMat]
        let lHorn = SCNNode(geometry: hornGeo)
        lHorn.position = SCNVector3(-0.45 * s, 2.58 * s, 0)
        lHorn.eulerAngles = SCNVector3(0, 0, 0.32)
        let rHorn = SCNNode(geometry: hornGeo)
        rHorn.position = SCNVector3(0.45 * s, 2.58 * s, 0)
        rHorn.eulerAngles = SCNVector3(0, 0, -0.32)
        root.addChildNode(lHorn)
        root.addChildNode(rHorn)

        let lArm = makeBoxNode(w: 0.48 * s, h: 1.25 * s, l: 0.48 * s, x: -1.05 * s, y: 1.15 * s, z: -0.25 * s, color: .systemRed)
        let rArm = makeBoxNode(w: 0.48 * s, h: 1.25 * s, l: 0.48 * s, x: 1.05 * s, y: 1.15 * s, z: -0.25 * s, color: .systemRed)
        root.addChildNode(lArm)
        root.addChildNode(rArm)
        bossLeftArm = lArm
        bossRightArm = rArm

        root.addChildNode(makeBoxNode(w: 0.46 * s, h: 0.85 * s, l: 0.52 * s, x: -0.44 * s, y: 0.42 * s, z: 0, color: .black))
        root.addChildNode(makeBoxNode(w: 0.46 * s, h: 0.85 * s, l: 0.52 * s, x: 0.44 * s, y: 0.42 * s, z: 0, color: .black))
        return root
    }

    private func syncScene(with snap: GameSnapshot) {
        SCNTransaction.begin()
        SCNTransaction.disableActions = true

        if builtStageRevision != snap.stageRevision {
            builtStageRevision = snap.stageRevision
            rebuildStageGeometry(for: snap)
        }

        // 1. Update Hero Skin & Crest if changed
        let skin = snap.activeSkin
        if currentCrestStyle != skin.crestStyle {
            currentCrestStyle = skin.crestStyle
            rebuildCrest(style: skin.crestStyle)
        }

        let armorColor = snap.isOverdriveActive ? UIColor(red: 1.0, green: 0.82, blue: 0.16, alpha: 1.0) : skin.primaryArmor.uiColor
        primaryMat.diffuse.contents = armorColor
        darkLimbMat.diffuse.contents = armorColor.withAlphaComponent(0.9)
        trimMat.diffuse.contents = skin.secondaryTrim.uiColor

        let zoneCoreColor: UIColor
        if snap.isOverdriveActive {
            zoneCoreColor = .yellow
        } else {
            switch snap.scaleZone {
            case .micro: zoneCoreColor = .cyan
            case .sync: zoneCoreColor = skin.coreGlow.uiColor
            case .titan: zoneCoreColor = .orange
            }
        }
        coreMat.diffuse.contents = zoneCoreColor

        // 2. Hero Transform & Limb Animation
        let blinkHide = snap.invulnerableTimer > 0 && (Int(snap.simClock * 18) % 2 == 0)
        heroRootNode.isHidden = blinkHide

        let s = snap.currentScale
        let isMoving = (snap.phase == .running || snap.phase == .goalGauntlet)
        let swingRad = isMoving ? sin(snap.runCyclePhase) * 0.65 : sin(snap.runCyclePhase) * 0.14
        let bobY = isMoving ? abs(cos(snap.runCyclePhase)) * 0.08 * s : 0

        heroRootNode.position = SCNVector3(0, snap.playerY, snap.playerZ)
        shadowNode.scale = SCNVector3(s, 1, s)
        heroBodyPivot.position = SCNVector3(0, bobY, 0)
        heroBodyPivot.scale = SCNVector3(s, s, s)

        leftLegPivot.eulerAngles = SCNVector3(swingRad, 0, 0)
        rightLegPivot.eulerAngles = SCNVector3(-swingRad, 0, 0)
        leftArmPivot.eulerAngles = SCNVector3(-swingRad, 0, 0)
        rightArmPivot.eulerAngles = SCNVector3(swingRad, 0, 0)
        shieldBubbleNode.isHidden = !snap.hasShield

        // 3. Collectibles spin & visibility
        let spinY = snap.simClock * 3.0
        for orb in snap.collectibles {
            if let node = collectibleNodes[orb.id] {
                node.isHidden = orb.collected
                if !orb.collected {
                    let bob = sin(snap.simClock * 4.5 + orb.z * 0.4) * 0.14
                    node.position = SCNVector3(orb.x, orb.y + bob, orb.z)
                    node.eulerAngles = SCNVector3(0, spinY, 0)
                }
            }
        }

        // 4. Obstacles dynamic state
        for obs in snap.obstacles {
            guard let node = obstacleNodes[obs.id] else { continue }
            if obs.shatteredOrSmashed && (obs.type == .heavyBrickWall || obs.type == .fragileGlassBridge || obs.type == .rivalEnforcer) {
                node.isHidden = true
                continue
            }
            if obs.type == .rivalEnforcer {
                node.position = SCNVector3(obs.lateralX, 0, obs.zStart + obs.zLength * 0.5)
            } else if obs.type == .movingPistonGate, let beam = pistonBeamNodes[obs.id] {
                let openFactor = 0.5 + 0.5 * sin(snap.simClock * 3.1 + obs.phaseOffset)
                beam.position = SCNVector3(0, openFactor * 1.65, 0)
            } else if obs.type == .precisionSyncArch, let mat = syncArchMaterials[obs.id] {
                let matched = snap.currentScale >= obs.minSafeScale && snap.currentScale <= obs.maxSafeScale
                mat.diffuse.contents = matched ? UIColor.systemGreen : UIColor.systemRed
            }
        }

        // 5. Multiplier Walls
        for wall in snap.multiplierWalls {
            multiplierWallNodes[wall.index]?.isHidden = wall.broken
        }

        // 6. Boss Node & Clash Beam
        if let boss = snap.boss, let bNode = bossRootNode {
            bNode.position = SCNVector3(0, boss.bossFlyHeight, boss.bossZ + boss.bossFlyOffsetZ)
            let armSwing = sin(snap.simClock * 6.0) * 0.38
            bossLeftArm?.eulerAngles = SCNVector3(armSwing, 0, 0)
            bossRightArm?.eulerAngles = SCNVector3(-armSwing, 0, 0)
            clashBeamNode?.isHidden = !(snap.phase == .bossClash && !boss.bossDefeated)
        }

        // 7. 3D Tumbling Debris
        let activeIds = Set(snap.debris.map { $0.id })
        for (id, node) in debrisPoolNodes where !activeIds.contains(id) {
            node.removeFromParentNode()
            debrisPoolNodes.removeValue(forKey: id)
        }
        for d in snap.debris {
            let dNode: SCNNode
            if let existing = debrisPoolNodes[d.id] {
                dNode = existing
            } else {
                let created = makeBoxNode(w: d.size, h: d.size, l: d.size, x: d.x, y: d.y, z: d.z, color: d.color.uiColor)
                debrisContainerNode.addChildNode(created)
                debrisPoolNodes[d.id] = created
                dNode = created
            }
            dNode.position = SCNVector3(d.x, d.y, d.z)
            dNode.eulerAngles = SCNVector3(d.rotX * .pi / 180, d.rotY * .pi / 180, 0)
        }

        // 8. Dynamic Camera Chase & Orbit
        updateCamera(snap: snap)

        SCNTransaction.commit()
    }

    private func updateCamera(snap: GameSnapshot) {
        let shakeX = snap.cameraShake > 0 ? sin(snap.simClock * 47) * snap.cameraShake * 0.45 : 0
        let shakeY = snap.cameraShake > 0 ? cos(snap.simClock * 53) * snap.cameraShake * 0.35 : 0

        switch snap.phase {
        case .home:
            let heroH = max(1.1, snap.currentScale * 1.35)
            let camDist: Float = 6.2 + snap.currentScale * 1.4
            let angle = sin(snap.homeOrbitAngle) * 0.48
            let eyeX = sin(angle) * camDist
            let eyeZ = -cos(angle) * camDist
            let eyeY: Float = 2.6 + snap.currentScale * 0.95
            cameraNode.position = SCNVector3(eyeX, eyeY, eyeZ)
            cameraNode.look(at: SCNVector3(0, heroH, 0.5), up: SCNVector3(0, 1, 0), localFront: SCNVector3(0, 0, -1))

        case .bossClash:
            let bossZ = snap.boss?.bossZ ?? (snap.playerZ + 6.0)
            let midZ = (snap.playerZ + bossZ) * 0.5
            cameraNode.position = SCNVector3(4.8 + shakeX, 5.4 + shakeY, snap.playerZ - 7.8)
            cameraNode.look(at: SCNVector3(0, 2.8, midZ), up: SCNVector3(0, 1, 0), localFront: SCNVector3(0, 0, -1))

        default:
            let scalePull = max(-0.4, snap.currentScale - 1.0)
            let eyeX: Float = 0.65 + shakeX
            let eyeY: Float = 4.6 + scalePull * 1.45 + snap.playerY * 0.45 + shakeY
            let eyeZ: Float = snap.playerZ - (8.8 + scalePull * 2.15)
            let lookY: Float = 1.6 + scalePull * 0.85 + snap.playerY * 0.5
            let lookZ: Float = snap.playerZ + 8.5
            cameraNode.position = SCNVector3(eyeX, eyeY, eyeZ)
            cameraNode.look(at: SCNVector3(0, lookY, lookZ), up: SCNVector3(0, 1, 0), localFront: SCNVector3(0, 0, -1))
        }
    }

    private func makeBoxNode(
        w: Float, h: Float, l: Float,
        x: Float, y: Float, z: Float,
        color: UIColor,
        emissive: Bool = false
    ) -> SCNNode {
        let box = SCNBox(width: CGFloat(w), height: CGFloat(h), length: CGFloat(l), chamferRadius: 0.03)
        let mat = SCNMaterial()
        if emissive {
            mat.lightingModel = .constant
            mat.diffuse.contents = color
        } else {
            mat.lightingModel = .blinn
            mat.diffuse.contents = color
        }
        box.materials = [mat]
        let node = SCNNode(geometry: box)
        node.position = SCNVector3(x, y, z)
        return node
    }
}
