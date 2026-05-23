package com.quackster.chroma;

import com.quackster.chroma.extractor.FurniExtractor;
import com.quackster.chroma.util.FileUtil;
import com.quackster.chroma.util.ImageUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;
import java.util.*;
import java.util.stream.Collectors;
import com.madgag.gif.fmsware.AnimatedGifEncoder;

/**
 * Main class for rendering Habbo furniture from SWF files
 */
public class ChromaFurniture {
    private String fileName;
    private String outputFileName;
    private boolean isSmallFurni;
    
    private int renderState;
    private int renderDirection;
    private int requestedRenderDirection;
    private int colourId;
    private String sprite;
    private List<ChromaAsset> assets;
    private BufferedImage drawingCanvas;
    
    private static final int CANVAS_WIDTH = 1200;
    private static final int CANVAS_HEIGHT = 1200;
    private static final String CANVAS_PICTURE = "bg.png";
    
    private String furniData;
    private boolean renderShadows;
    private boolean renderBackground;
    private String renderCanvasColour;
    private boolean cropImage;
    private boolean isIcon;
    private boolean generateGif;
    private boolean mirrorFallbackH;
    private int animationCount;
    private TreeMap<Integer, ChromaAnimation> animations;
    private int highestAnimationLayer;
    private int maxStates;

    public ChromaFurniture(String inputFileName, boolean isSmallFurni, int renderState, 
                          int renderDirection, int colourId, boolean renderShadows,
                          boolean renderBackground, String renderCanvasColour, 
                          boolean cropImage, boolean renderIcon) {
        this(inputFileName, isSmallFurni, renderState, renderDirection, colourId, 
             renderShadows, renderBackground, renderCanvasColour, cropImage, renderIcon, false);
    }
    
    public ChromaFurniture(String inputFileName, boolean isSmallFurni, int renderState, 
                          int renderDirection, int colourId, boolean renderShadows,
                          boolean renderBackground, String renderCanvasColour, 
                          boolean cropImage, boolean renderIcon, boolean generateGif) {
        this.fileName = inputFileName;
        this.isSmallFurni = isSmallFurni;
        this.assets = new ArrayList<>();
        this.renderState = renderState;
        this.renderDirection = renderDirection;
        this.requestedRenderDirection = renderDirection;
        this.colourId = colourId;
        this.sprite = getFileNameWithoutExtension(inputFileName);
        this.outputFileName = getFileName();
        this.furniData = Paths.get("furni_export", getFileNameWithoutExtension(inputFileName), "furni.json").toString();
        this.renderShadows = renderShadows;
        this.renderBackground = renderBackground;
        this.renderCanvasColour = renderCanvasColour;
        this.cropImage = cropImage;
        this.isIcon = renderIcon;
        this.generateGif = generateGif;
        this.animations = new TreeMap<>();
    }

    public String run() {
        FurniExtractor.parse(this.fileName);
        
        if (this.renderBackground) {
            try {
                File bgFile = new File(CANVAS_PICTURE);
                if (bgFile.exists()) {
                    drawingCanvas = ImageIO.read(bgFile);
                } else {
                    drawingCanvas = new BufferedImage(CANVAS_WIDTH, CANVAS_HEIGHT, BufferedImage.TYPE_INT_ARGB);
                }
            } catch (IOException e) {
                drawingCanvas = new BufferedImage(CANVAS_WIDTH, CANVAS_HEIGHT, BufferedImage.TYPE_INT_ARGB);
            }
        } else {
            drawingCanvas = new BufferedImage(CANVAS_WIDTH, CANVAS_HEIGHT, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = drawingCanvas.createGraphics();
            g.setColor(hexToColor(this.renderCanvasColour));
            g.fillRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT);
            g.dispose();
        }
        
        generateAnimations();
        generateAssets();
        
        createBuildQueue();
        
        this.outputFileName = getFileName();
        return this.outputFileName;
    }

    private void generateAnimations() {
        Document xmlData = FileUtil.solveXmlFile(getXmlDirectory(), "visualization");
        
        this.animationCount = 0;
        this.animations = new TreeMap<>();
        
        if (xmlData == null) {
            return;
        }
        
        String size = isSmallFurni ? "32" : "64";
        NodeList frames = findFrames(xmlData, size);
        
        this.highestAnimationLayer = 0;
        
        if (frames != null) {
            for (int i = 0; i < frames.getLength(); i++) {
                Node frame = frames.item(i);
                
                Node animationLayerNode = frame.getParentNode().getParentNode();
                Node layerIdAttr = animationLayerNode.getAttributes().getNamedItem("id");
                
                if (layerIdAttr == null) continue;
                
                int letterPosition = Integer.parseInt(layerIdAttr.getNodeValue());
                
                if (letterPosition < 0 || letterPosition >= 26) {
                    continue;
                }
                
                int animationLetter = letterPosition;
                highestAnimationLayer = Math.max(highestAnimationLayer, letterPosition + 1);
                
                Node animationNode = animationLayerNode.getParentNode();
                Node animIdAttr = animationNode.getAttributes().getNamedItem("id");
                
                if (animIdAttr == null) continue;
                
                int animationId = Integer.parseInt(animIdAttr.getNodeValue());
                int castAnimationId = animationId + 1;
                
                if (castAnimationId > this.animationCount) {
                    this.animationCount = castAnimationId;
                }
                
                if (!this.animations.containsKey(animationLetter)) {
                    this.animations.put(animationLetter, new ChromaAnimation());
                }
                
                if (!this.animations.get(animationLetter).getStates().containsKey(animationId)) {
                    ChromaFrame frameClass = new ChromaFrame();
                    this.animations.get(animationLetter).getStates().put(animationId, frameClass);
                    
                    Node loopCountAttr = animationLayerNode.getAttributes().getNamedItem("loopCount");
                    if (loopCountAttr != null) {
                        frameClass.setLoop(Integer.parseInt(loopCountAttr.getNodeValue()));
                    }
                    
                    Node frameRepeatAttr = animationLayerNode.getAttributes().getNamedItem("frameRepeat");
                    if (frameRepeatAttr != null) {
                        frameClass.setFramesPerSecond(Integer.parseInt(frameRepeatAttr.getNodeValue()));
                    }
                }
                
                Node frameIdAttr = frame.getAttributes().getNamedItem("id");
                if (frameIdAttr != null) {
                    this.animations.get(animationLetter).getStates().get(animationId).getFrames().add(frameIdAttr.getNodeValue());
                }
            }
        }
    }

    private NodeList findFrames(Document xmlData, String size) {
        try {
            NodeList visualizations = xmlData.getElementsByTagName("visualization");
            for (int i = 0; i < visualizations.getLength(); i++) {
                Node viz = visualizations.item(i);
                Node sizeAttr = viz.getAttributes().getNamedItem("size");
                
                if (sizeAttr != null && sizeAttr.getNodeValue().equals(size)) {
                    NodeList animations = ((org.w3c.dom.Element) viz).getElementsByTagName("animation");
                    if (animations.getLength() > 0) {
                        // Collect all frame nodes
                        List<Node> frameNodes = new ArrayList<>();
                        for (int j = 0; j < animations.getLength(); j++) {
                            Node anim = animations.item(j);
                            NodeList animLayers = ((org.w3c.dom.Element) anim).getElementsByTagName("animationLayer");
                            for (int k = 0; k < animLayers.getLength(); k++) {
                                Node animLayer = animLayers.item(k);
                                NodeList frameSeqs = ((org.w3c.dom.Element) animLayer).getElementsByTagName("frameSequence");
                                for (int l = 0; l < frameSeqs.getLength(); l++) {
                                    Node frameSeq = frameSeqs.item(l);
                                    NodeList frames = ((org.w3c.dom.Element) frameSeq).getElementsByTagName("frame");
                                    for (int m = 0; m < frames.getLength(); m++) {
                                        frameNodes.add(frames.item(m));
                                    }
                                }
                            }
                        }
                        
                        if (!frameNodes.isEmpty()) {
                            return new NodeListWrapper(frameNodes);
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private void generateAssets() {
        Document xmlData = FileUtil.solveXmlFile(getXmlDirectory(), "assets");
        
        if (xmlData == null) {
            return;
        }
        
        NodeList assetNodes = xmlData.getElementsByTagName("asset");
        
        for (int i = 0; i < assetNodes.getLength(); i++) {
            Node asset = assetNodes.item(i);
            
            Node xAttr = asset.getAttributes().getNamedItem("x");
            Node yAttr = asset.getAttributes().getNamedItem("y");
            Node nameAttr = asset.getAttributes().getNamedItem("name");
            
            if (xAttr == null || yAttr == null || nameAttr == null) continue;
            
            int x = Integer.parseInt(xAttr.getNodeValue());
            int y = Integer.parseInt(yAttr.getNodeValue());
            String imageName = nameAttr.getNodeValue();
            
            if (imageName.contains(".props") || imageName.startsWith("s_" + this.sprite)) {
                continue;
            }
            
            if (!isIcon) {
                if (imageName.contains("_icon_")) continue;
            } else {
                if (!imageName.contains("_icon_")) continue;
            }
            
            Node sourceAttr = asset.getAttributes().getNamedItem("source");
            ChromaAsset chromaAsset;
            
            if (sourceAttr != null) {
                chromaAsset = new ChromaAsset(this, x, y, sourceAttr.getNodeValue(), imageName);
            } else {
                chromaAsset = new ChromaAsset(this, x, y, null, imageName);
            }
            
            createAsset(chromaAsset, asset, true);
        }
        
        // Calculate max states
        this.maxStates = 0;
        Document visualization = FileUtil.solveXmlFile(getXmlDirectory(), "visualization");
        
        if (visualization != null) {
            NodeList animations = findAnimations(visualization, isSmallFurni ? "32" : "64");
            
            if (animations != null) {
                for (int i = 0; i < animations.getLength(); i++) {
                    Node animation = animations.item(i);
                    Node idAttr = animation.getAttributes().getNamedItem("id");
                    
                    if (idAttr != null) {
                        int state = Integer.parseInt(idAttr.getNodeValue());
                        if (state > maxStates) {
                            maxStates = state;
                        }
                    }
                }
            }
        }
        
        this.highestAnimationLayer = assets.stream()
            .filter(x -> !x.isShadow())
            .mapToInt(ChromaAsset::getLayer)
            .max()
            .orElse(0) + 1;
        
        // Fill in missing animation states
        for (int i = 0; i < highestAnimationLayer; i++) {
            if (!this.animations.containsKey(i)) {
                ChromaAnimation animation = new ChromaAnimation();
                this.animations.put(i, animation);
                
                for (int j = 0; j < this.animationCount; j++) {
                    if (!animation.getStates().containsKey(j)) {
                        ChromaFrame frame = new ChromaFrame();
                        frame.getFrames().add("0");
                        animation.getStates().put(j, frame);

                        
                    }
                }
            }
        }
    }

    private NodeList findAnimations(Document xmlData, String size) {
        try {
            List<Node> animNodes = new ArrayList<>();
            NodeList visualizations = xmlData.getElementsByTagName("visualization");
            
            for (int i = 0; i < visualizations.getLength(); i++) {
                Node viz = visualizations.item(i);
                Node sizeAttr = viz.getAttributes().getNamedItem("size");
                
                if (sizeAttr != null && sizeAttr.getNodeValue().equals(size)) {
                    NodeList anims = ((org.w3c.dom.Element) viz).getElementsByTagName("animation");
                    for (int j = 0; j < anims.getLength(); j++) {
                        animNodes.add(anims.item(j));
                    }
                }
            }
            
            return animNodes.isEmpty() ? null : new NodeListWrapper(animNodes);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private void createAsset(ChromaAsset chromaAsset, Node node, boolean createFiles) {
        if (!chromaAsset.parse()) {
            return;
        }
        
        boolean exists = assets.stream().anyMatch(x -> x.getImageName().equals(chromaAsset.getImageName()));
        
        if (!exists) {
            Node flipHAttr = node.getAttributes().getNamedItem("flipH");
            chromaAsset.setFlipH(flipHAttr != null && flipHAttr.getNodeValue().equals("1"));
            assets.add(chromaAsset);
            
            if (chromaAsset.getSourceImage() != null && createFiles) {
                chromaAsset.generateImage();
            }
            
            chromaAsset.setImageX(chromaAsset.getImageX() + (CANVAS_WIDTH / 2));
            chromaAsset.setImageY(chromaAsset.getImageY() + (CANVAS_HEIGHT / 2));
        }
        
        if (chromaAsset.getImageName().contains("_sd_")) {
            chromaAsset.setShadow(true);
            chromaAsset.setZ(Integer.MIN_VALUE);
        } else {
            chromaAsset.setZ(chromaAsset.getZ() + chromaAsset.getLayer());
        }
    }

    private List<ChromaAsset> createBuildQueue() {
        if (renderState > maxStates) {
            renderState = 0;
        }
        
        List<ChromaAsset> candidates = assets.stream()
            .filter(x -> x.isSmall() == isSmallFurni)
            .collect(Collectors.toList());
        
        List<ChromaAsset> validDirections = selectFallbackDirection(candidates, requestedRenderDirection);
        
        candidates = validDirections;
        List<ChromaAsset> renderFrames = new ArrayList<>();
        
        for (int layer = 0; layer < this.highestAnimationLayer; layer++) {
            int frame = 0;
            
            if (animations.containsKey(layer) &&
                !animations.get(layer).getStates().isEmpty() &&
                animations.get(layer).getStates().containsKey(renderState)) {
                
                ChromaFrame frameData = animations.get(layer).getStates().get(renderState);
                if (!frameData.getFrames().isEmpty()) {
                    frame = Integer.parseInt(frameData.getFrames().get(0));
                }
            }
            
            final int frameToFind = frame;
            final int layerToFind = layer;
            renderFrames.addAll(candidates.stream()
                .filter(x -> x.getLayer() == layerToFind && x.getFrame() == frameToFind)
                .collect(Collectors.toList()));
        }
        
        if (!this.renderShadows) {
            renderFrames = renderFrames.stream()
                .filter(x -> !x.isShadow())
                .collect(Collectors.toList());
        }
        
        renderFrames.sort(Comparator.comparingInt(ChromaAsset::getZ));
        return renderFrames;
    }

    public byte[] createImage() {
        if (this.generateGif) {
            return createGif();
        }
        
        List<ChromaAsset> buildQueue = createBuildQueue();
        
        if (buildQueue == null || buildQueue.isEmpty()) {
            return null;
        }
        
        List<Color> cropColours = new ArrayList<>();
        
        if (this.cropImage) {
            if (this.renderBackground) {
                cropColours.add(new Color(142, 142, 94));
                cropColours.add(new Color(152, 152, 101));
            } else {
                cropColours.add(hexToColor(this.renderCanvasColour));
            }
        }
        
        BufferedImage canvas = copyImage(drawingCanvas);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        
        for (ChromaAsset asset : buildQueue) {
            String imagePath = asset.getImagePath();
            if (imagePath == null) continue;
            
            try {
                BufferedImage image = ImageIO.read(new File(imagePath));
                
                if (asset.getAlpha() != -1) {
                    image = tintImage(image, "FFFFFF", asset.getAlpha());
                }
                
                if (asset.getColourCode() != null) {
                    image = tintImage(image, asset.getColourCode(), 255);
                }
                
                if (asset.isShadow()) {
                    image = applyOpacity(image, 0.2f);
                }
                
                // Handle different ink modes
                int x = canvas.getWidth() - asset.getImageX();
                int y = canvas.getHeight() - asset.getImageY();
                
                if ("ADD".equals(asset.getInk()) || "33".equals(asset.getInk())) {
                    // Add Pin (33): Add RGB values and clamp to 255
                    applyAddPinBlending(canvas, image, x, y);
                } else {
                    applyNormalBlending(canvas, image, x, y);
                }
                
            } catch (IOException e) {
                System.err.println("Error loading image: " + imagePath);
                e.printStackTrace();
            }
        }
        
        g.dispose();
        
        BufferedImage finalImage = canvas;
        
        if (cropImage && !cropColours.isEmpty()) {
            finalImage = ImageUtil.trimBitmap(canvas, cropColours.toArray(new Color[0]));
        }

        if (mirrorFallbackH) {
            finalImage = flipHorizontal(finalImage);
        }
        
        return renderImage(finalImage);
    }

    private byte[] renderImage(BufferedImage image) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Collects all unique frame combinations from all animation layers for the current render state
     */
    private List<Map<Integer, Integer>> collectAllFrameCombinations() {
        List<Map<Integer, Integer>> frameCombinations = new ArrayList<>();
        
        // Collect frames for each layer from the current render state
        List<List<Integer>> layerFrames = new ArrayList<>();
        int maxFramesInAnyLayer = 0;
        
        for (int layer = 0; layer < this.highestAnimationLayer; layer++) {
            List<Integer> framesForLayer = new ArrayList<>();
            
            if (animations.containsKey(layer) &&
                !animations.get(layer).getStates().isEmpty() &&
                animations.get(layer).getStates().containsKey(renderState)) {
                
                ChromaFrame frameData = animations.get(layer).getStates().get(renderState);
                for (String frameIdStr : frameData.getFrames()) {
                    try {
                        int frameId = Integer.parseInt(frameIdStr);
                        if (!framesForLayer.contains(frameId)) {
                            framesForLayer.add(frameId);
                        }
                    } catch (NumberFormatException e) {
                        // Skip invalid frame IDs
                    }
                }
            }
            
            // If no frames found for this layer, use default frame 0
            if (framesForLayer.isEmpty()) {
                framesForLayer.add(0);
            }
            
            layerFrames.add(framesForLayer);
            maxFramesInAnyLayer = Math.max(maxFramesInAnyLayer, framesForLayer.size());
        }
        
        // Generate frame combinations by cycling through all frames
        // Use the maximum number of frames to ensure we cycle through all available frames
        for (int frameIndex = 0; frameIndex < maxFramesInAnyLayer; frameIndex++) {
            Map<Integer, Integer> combination = new HashMap<>();
            for (int layer = 0; layer < this.highestAnimationLayer; layer++) {
                List<Integer> framesForLayer = layerFrames.get(layer);
                // Cycle through frames for this layer
                int frameIndexForLayer = frameIndex % framesForLayer.size();
                combination.put(layer, framesForLayer.get(frameIndexForLayer));
            }
            frameCombinations.add(combination);
        }
        
        // If we still have no combinations, create a default one
        if (frameCombinations.isEmpty()) {
            Map<Integer, Integer> defaultCombination = new HashMap<>();
            for (int layer = 0; layer < this.highestAnimationLayer; layer++) {
                defaultCombination.put(layer, 0);
            }
            frameCombinations.add(defaultCombination);
        }
        
        return frameCombinations;
    }

    /**
     * Creates a build queue for a specific frame combination
     */
    private List<ChromaAsset> createBuildQueueForFrames(Map<Integer, Integer> frameMap) {
        List<ChromaAsset> candidates = assets.stream()
            .filter(x -> x.isSmall() == isSmallFurni)
            .collect(Collectors.toList());
        
        List<ChromaAsset> validDirections = selectFallbackDirection(candidates, requestedRenderDirection);
        
        candidates = validDirections;
        List<ChromaAsset> renderFrames = new ArrayList<>();
        
        for (int layer = 0; layer < this.highestAnimationLayer; layer++) {
            int frame = frameMap.getOrDefault(layer, 0);
            
            final int frameToFind = frame;
            final int layerToFind = layer;
            renderFrames.addAll(candidates.stream()
                .filter(x -> x.getLayer() == layerToFind && x.getFrame() == frameToFind)
                .collect(Collectors.toList()));
        }
        
        if (!this.renderShadows) {
            renderFrames = renderFrames.stream()
                .filter(x -> !x.isShadow())
                .collect(Collectors.toList());
        }
        
        renderFrames.sort(Comparator.comparingInt(ChromaAsset::getZ));
        return renderFrames;
    }

    /**
     * Renders a single frame image with a specific frame combination
     */
    private BufferedImage renderFrameImage(Map<Integer, Integer> frameMap) {
        List<ChromaAsset> buildQueue = createBuildQueueForFrames(frameMap);
        
        if (buildQueue == null || buildQueue.isEmpty()) {
            return null;
        }
        
        List<Color> cropColours = new ArrayList<>();
        
        if (this.cropImage) {
            if (this.renderBackground) {
                cropColours.add(new Color(142, 142, 94));
                cropColours.add(new Color(152, 152, 101));
            } else {
                cropColours.add(hexToColor(this.renderCanvasColour));
            }
        }
        
        BufferedImage canvas = copyImage(drawingCanvas);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        
        for (ChromaAsset asset : buildQueue) {
            String imagePath = asset.getImagePath();
            if (imagePath == null) continue;
            
            try {
                BufferedImage image = ImageIO.read(new File(imagePath));
                
                if (asset.getAlpha() != -1) {
                    image = tintImage(image, "FFFFFF", asset.getAlpha());
                }
                
                if (asset.getColourCode() != null) {
                    image = tintImage(image, asset.getColourCode(), 255);
                }
                
                if (asset.isShadow()) {
                    image = applyOpacity(image, 0.2f);
                }
                
                // Handle different ink modes
                int x = canvas.getWidth() - asset.getImageX();
                int y = canvas.getHeight() - asset.getImageY();
                
                if ("ADD".equals(asset.getInk()) || "33".equals(asset.getInk())) {
                    // Add Pin (33): Add RGB values and clamp to 255
                    applyAddPinBlending(canvas, image, x, y);
                } else {
                    applyNormalBlending(canvas, image, x, y);
                }
                
            } catch (IOException e) {
                System.err.println("Error loading image: " + imagePath);
                e.printStackTrace();
            }
        }
        
        g.dispose();
        
        BufferedImage finalImage = canvas;
        
        if (cropImage && !cropColours.isEmpty()) {
            finalImage = ImageUtil.trimBitmap(canvas, cropColours.toArray(new Color[0]));
        }

        if (mirrorFallbackH) {
            finalImage = flipHorizontal(finalImage);
        }
        
        return finalImage;
    }

    private List<ChromaAsset> selectFallbackDirection(List<ChromaAsset> candidates, int requestedDirection) {
        mirrorFallbackH = false;

        List<ChromaAsset> validDirections = candidates.stream()
            .filter(x -> x.getDirection() == requestedDirection)
            .collect(Collectors.toList());

        if (!validDirections.isEmpty()) {
            return validDirections;
        }

        if (requestedDirection == 0) {
            validDirections = candidates.stream().filter(x -> x.getDirection() == 4).collect(Collectors.toList());
            if (!validDirections.isEmpty()) {
                renderDirection = 4;
                mirrorFallbackH = true;
                return validDirections;
            }
        }

        for (int direction : new int[] {0, 2, 4, 6}) {
            validDirections = candidates.stream().filter(x -> x.getDirection() == direction).collect(Collectors.toList());
            if (!validDirections.isEmpty()) {
                renderDirection = direction;
                return validDirections;
            }
        }

        return validDirections;
    }

    private BufferedImage flipHorizontal(BufferedImage image) {
        BufferedImage flipped = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = flipped.createGraphics();
        g.drawImage(image, image.getWidth(), 0, -image.getWidth(), image.getHeight(), null);
        g.dispose();
        return flipped;
    }

    /**
     * Creates a GIF by cycling through all available frames
     */
    private byte[] createGif() {
        List<Map<Integer, Integer>> frameCombinations = collectAllFrameCombinations();
        
        if (frameCombinations.isEmpty()) {
            // Fallback to single frame if no combinations found
            return createImage();
        }
        
        List<BufferedImage> frames = new ArrayList<>();
        
        // Render each frame combination
        for (Map<Integer, Integer> frameMap : frameCombinations) {
            BufferedImage frameImage = renderFrameImage(frameMap);
            if (frameImage != null) {
                frames.add(frameImage);
            }
        }
        
        if (frames.isEmpty()) {
            return null;
        }
        
        // If only one frame, return as PNG
        if (frames.size() == 1) {
            return renderImage(frames.get(0));
        }
        
        // Create GIF from frames
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            AnimatedGifEncoder encoder = new AnimatedGifEncoder();
            encoder.setRepeat(0); // 0 = infinite loop
            encoder.setDelay(100); // 100ms delay between frames (10 fps)
            encoder.start(baos);
            
            // Write all frames
            for (BufferedImage frame : frames) {
                encoder.addFrame(frame);
            }
            
            encoder.finish();
            return baos.toByteArray();
        } catch (Exception e) {
            System.err.println("Error creating GIF: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private BufferedImage tintImage(BufferedImage image, String colourCode, int alpha) {
        Color rgb = hexToColor(colourCode);
        BufferedImage tinted = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                int pixel = image.getRGB(x, y);
                Color current = new Color(pixel, true);
                
                if (current.getAlpha() > 0) {
                    int r = (rgb.getRed() * current.getRed()) / 255;
                    int g = (rgb.getGreen() * current.getGreen()) / 255;
                    int b = (rgb.getBlue() * current.getBlue()) / 255;
                    
                    Color newColor = new Color(r, g, b, alpha);
                    tinted.setRGB(x, y, newColor.getRGB());
                } else {
                    tinted.setRGB(x, y, pixel);
                }
            }
        }
        
        return tinted;
    }

    private BufferedImage applyOpacity(BufferedImage image, float opacity) {
        BufferedImage result = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = result.createGraphics();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity));
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return result;
    }

    private void applyNormalBlending(BufferedImage canvas, BufferedImage foreground, int x, int y) {
        int canvasWidth = canvas.getWidth();
        int canvasHeight = canvas.getHeight();
        int fgWidth = foreground.getWidth();
        int fgHeight = foreground.getHeight();

        int startX = Math.max(0, x);
        int startY = Math.max(0, y);
        int endX = Math.min(canvasWidth, x + fgWidth);
        int endY = Math.min(canvasHeight, y + fgHeight);

        for (int cy = startY; cy < endY; cy++) {
            for (int cx = startX; cx < endX; cx++) {
                Color fgColor = new Color(foreground.getRGB(cx - x, cy - y), true);
                int fgAlpha = fgColor.getAlpha();
                if (fgAlpha == 0) {
                    continue;
                }

                Color bgColor = new Color(canvas.getRGB(cx, cy), true);
                int alpha = blendNormalAlpha(fgAlpha, bgColor.getAlpha());
                int r = blendNormalChannel(fgColor.getRed(), fgAlpha, bgColor.getRed(), bgColor.getAlpha(), alpha);
                int g = blendNormalChannel(fgColor.getGreen(), fgAlpha, bgColor.getGreen(), bgColor.getAlpha(), alpha);
                int b = blendNormalChannel(fgColor.getBlue(), fgAlpha, bgColor.getBlue(), bgColor.getAlpha(), alpha);

                canvas.setRGB(cx, cy, new Color(r, g, b, alpha).getRGB());
            }
        }
    }

    private int blendNormalAlpha(int fgAlpha, int bgAlpha) {
        double sourceAlpha = fgAlpha / 255.0;
        double backgroundAlpha = bgAlpha / 255.0;
        return clampChannel((int) Math.round((sourceAlpha + backgroundAlpha * (1.0 - sourceAlpha)) * 255.0));
    }

    private int blendNormalChannel(int fg, int fgAlpha, int bg, int bgAlpha, int alpha) {
        if (alpha == 0) {
            return 0;
        }
        double sourceAlpha = fgAlpha / 255.0;
        double backgroundAlpha = bgAlpha / 255.0;
        double outAlpha = alpha / 255.0;
        return clampChannel((int) Math.round((fg * sourceAlpha + bg * backgroundAlpha * (1.0 - sourceAlpha)) / outAlpha));
    }

    private int clampChannel(int value) {
        return Math.max(0, Math.min(255, value));
    }

    /**
     * Applies Add Pin (33) blending mode: Adds RGB values of foreground to background
     * and clamps each component to 255 (no overflow allowed).
     * 
     * @param canvas The background canvas to blend onto
     * @param foreground The foreground image to blend
     * @param x The x position to draw the foreground image
     * @param y The y position to draw the foreground image
     */
    private void applyAddPinBlending(BufferedImage canvas, BufferedImage foreground, int x, int y) {
        int canvasWidth = canvas.getWidth();
        int canvasHeight = canvas.getHeight();
        int fgWidth = foreground.getWidth();
        int fgHeight = foreground.getHeight();
        
        // Calculate the bounds of the intersection
        int startX = Math.max(0, x);
        int startY = Math.max(0, y);
        int endX = Math.min(canvasWidth, x + fgWidth);
        int endY = Math.min(canvasHeight, y + fgHeight);
        
        // Process each pixel in the intersection
        for (int cy = startY; cy < endY; cy++) {
            for (int cx = startX; cx < endX; cx++) {
                // Get background pixel
                int bgPixel = canvas.getRGB(cx, cy);
                Color bgColor = new Color(bgPixel, true);
                
                // Get foreground pixel (accounting for offset)
                int fgX = cx - x;
                int fgY = cy - y;
                int fgPixel = foreground.getRGB(fgX, fgY);
                Color fgColor = new Color(fgPixel, true);
                
                // Skip if foreground is fully transparent
                if (fgColor.getAlpha() == 0) {
                    continue;
                }
                
                // Add Pin blending: Add RGB values and clamp to 255
                int r = Math.min(255, bgColor.getRed() + fgColor.getRed());
                int g = Math.min(255, bgColor.getGreen() + fgColor.getGreen());
                int b = Math.min(255, bgColor.getBlue() + fgColor.getBlue());
                
                // For Add Pin blending, preserve the background alpha when foreground is opaque
                // When foreground is semi-transparent, blend the alpha channels
                int alpha;
                if (fgColor.getAlpha() == 255) {
                    // Fully opaque foreground - preserve background alpha
                    alpha = bgColor.getAlpha();
                } else if (bgColor.getAlpha() == 0) {
                    // Transparent background - use foreground alpha
                    alpha = fgColor.getAlpha();
                } else {
                    // Both have alpha - blend them (use maximum for additive effect)
                    alpha = Math.min(255, Math.max(bgColor.getAlpha(), fgColor.getAlpha()));
                }
                
                Color blendedColor = new Color(r, g, b, alpha);
                canvas.setRGB(cx, cy, blendedColor.getRGB());
            }
        }
    }

    private BufferedImage copyImage(BufferedImage source) {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return copy;
    }

    public static Color hexToColor(String hexString) {
        if ("transparent".equalsIgnoreCase(hexString)) {
            return new Color(0, 0, 0, 0);
        }
        
        try {
            hexString = hexString.replace("#", "");
            if (hexString.length() == 6) {
                int r = Integer.parseInt(hexString.substring(0, 2), 16);
                int g = Integer.parseInt(hexString.substring(2, 4), 16);
                int b = Integer.parseInt(hexString.substring(4, 6), 16);
                return new Color(r, g, b);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        return new Color(254, 254, 254);
    }

    public String getFileName() {
        String name = (isSmallFurni ? "s_" : "") + sprite + "_" + renderDirection + "_" + renderState;
        
        long coloredAssets = assets.stream().filter(x -> x.getColourCode() != null).count();
        if (this.colourId > -1 && coloredAssets > 0) {
            name += "_colour" + this.colourId;
        }
        
        return name + (generateGif ? ".gif" : ".png");
    }

    private String getFileNameWithoutExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        int lastSlash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        
        if (lastDot > lastSlash) {
            return fileName.substring(lastSlash + 1, lastDot);
        }
        return fileName.substring(lastSlash + 1);
    }

    // Getters
    public String getOutputDirectory() {
        return Paths.get("furni_export", sprite).toString();
    }

    public String getXmlDirectory() {
        return Paths.get("furni_export", sprite, "xml").toString();
    }

    public int getMaxStates() { return maxStates; }
    public boolean isSmallFurni() { return isSmallFurni; }
    public int getRenderDirection() { return renderDirection; }
    public int getColourId() { return colourId; }
    public String getSprite() { return sprite; }
    public boolean isIcon() { return isIcon; }
    public int getRenderState() { return renderState; }

    // Helper class for NodeList
    private static class NodeListWrapper implements NodeList {
        private final List<Node> nodes;
        
        public NodeListWrapper(List<Node> nodes) {
            this.nodes = nodes;
        }
        
        @Override
        public Node item(int index) {
            return index >= 0 && index < nodes.size() ? nodes.get(index) : null;
        }
        
        @Override
        public int getLength() {
            return nodes.size();
        }
    }
}
