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
    private int animationCount;
    private TreeMap<Integer, ChromaAnimation> animations;
    private int highestAnimationLayer;
    private int maxStates;

    public ChromaFurniture(String inputFileName, boolean isSmallFurni, int renderState, int renderDirection) {
        this(inputFileName, isSmallFurni, renderState, renderDirection, -1);
    }

    public ChromaFurniture(String inputFileName, boolean isSmallFurni, int renderState,
                          int renderDirection, int colourId) {
        this(inputFileName, isSmallFurni, renderState, renderDirection, colourId, false);
    }

    public ChromaFurniture(String inputFileName, boolean isSmallFurni, int renderState,
                          int renderDirection, int colourId, boolean renderShadows) {
        this(inputFileName, isSmallFurni, renderState, renderDirection, colourId, renderShadows, false);
    }

    public ChromaFurniture(String inputFileName, boolean isSmallFurni, int renderState,
                          int renderDirection, int colourId, boolean renderShadows,
                          boolean renderBackground) {
        this(inputFileName, isSmallFurni, renderState, renderDirection, colourId,
             renderShadows, renderBackground, "FEFEFE");
    }

    public ChromaFurniture(String inputFileName, boolean isSmallFurni, int renderState,
                          int renderDirection, int colourId, boolean renderShadows,
                          boolean renderBackground, String renderCanvasColour) {
        this(inputFileName, isSmallFurni, renderState, renderDirection, colourId,
             renderShadows, renderBackground, renderCanvasColour, true);
    }

    public ChromaFurniture(String inputFileName, boolean isSmallFurni, int renderState,
                          int renderDirection, int colourId, boolean renderShadows,
                          boolean renderBackground, String renderCanvasColour,
                          boolean cropImage) {
        this(inputFileName, isSmallFurni, renderState, renderDirection, colourId,
             renderShadows, renderBackground, renderCanvasColour, cropImage, false);
    }

    public ChromaFurniture(String inputFileName, boolean isSmallFurni, int renderState, 
                          int renderDirection, int colourId, boolean renderShadows,
                          boolean renderBackground, String renderCanvasColour, 
                          boolean cropImage, boolean renderIcon) {
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
        this.animations = new TreeMap<>();
    }

    public String run() {
        if (!FurniExtractor.parse(this.fileName)) {
            throw new RuntimeException("Failed to parse SWF file: " + this.fileName);
        }
        
        if (this.renderBackground) {
            try {
                drawingCanvas = ImageIO.read(new File(CANVAS_PICTURE));
                if (drawingCanvas == null) {
                    throw new IOException("Unsupported background image: " + CANVAS_PICTURE);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
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
        
        createBuildQueue(0);
        
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
                int letterPosition = Integer.parseInt(animationLayerNode.getAttributes().getNamedItem("id").getNodeValue());
                
                if (letterPosition < 0 || letterPosition > 26) {
                    continue;
                }
                
                int animationLetter = letterPosition;
                highestAnimationLayer = letterPosition + 1;
                
                Node animationNode = animationLayerNode.getParentNode();
                int animationId = Integer.parseInt(animationNode.getAttributes().getNamedItem("id").getNodeValue());
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
                
                this.animations.get(animationLetter).getStates().get(animationId).getFrames().add(frame.getAttributes().getNamedItem("id").getNodeValue());
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
                    Node animationsNode = firstDirectChild(viz, "animations");
                    if (animationsNode != null) {
                        NodeList animations = directChildren(animationsNode, "animation");
                        // Collect all frame nodes
                        List<Node> frameNodes = new ArrayList<>();
                        for (int j = 0; j < animations.getLength(); j++) {
                            Node anim = animations.item(j);
                            NodeList animLayers = directChildren(anim, "animationLayer");
                            for (int k = 0; k < animLayers.getLength(); k++) {
                                Node animLayer = animLayers.item(k);
                                NodeList frameSeqs = directChildren(animLayer, "frameSequence");
                                for (int l = 0; l < frameSeqs.getLength(); l++) {
                                    Node frameSeq = frameSeqs.item(l);
                                    NodeList frames = directChildren(frameSeq, "frame");
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
            
            int x = Integer.parseInt(asset.getAttributes().getNamedItem("x").getNodeValue());
            int y = Integer.parseInt(asset.getAttributes().getNamedItem("y").getNodeValue());
            String imageName = asset.getAttributes().getNamedItem("name").getNodeValue();
            
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
        
        if (visualization == null) {
            return;
        }

        NodeList animations = findAnimations(visualization, isSmallFurni ? "32" : "64");

        if (animations != null) {
            for (int i = 0; i < animations.getLength(); i++) {
                Node animation = animations.item(i);
                int state = Integer.parseInt(animation.getAttributes().getNamedItem("id").getNodeValue());
                if (state > maxStates) {
                    maxStates = state;
                }
            }
        }
        
        this.highestAnimationLayer = assets.stream()
            .filter(x -> !x.isShadow())
            .mapToInt(ChromaAsset::getLayer)
            .max()
            .orElseThrow() + 1;
        
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
            List<Node> globalAnimations = new ArrayList<>();
            List<Node> directionAnimations = new ArrayList<>();
            NodeList visualizations = xmlData.getElementsByTagName("visualization");
            
            for (int i = 0; i < visualizations.getLength(); i++) {
                Node viz = visualizations.item(i);
                Node sizeAttr = viz.getAttributes().getNamedItem("size");
                
                if (sizeAttr != null && sizeAttr.getNodeValue().equals(size)) {
                    Node animationsNode = firstDirectChild(viz, "animations");
                    if (animationsNode != null) {
                        NodeList anims = directChildren(animationsNode, "animation");
                        for (int j = 0; j < anims.getLength(); j++) {
                            globalAnimations.add(anims.item(j));
                        }
                    }

                    Node directionsNode = firstDirectChild(viz, "directions");
                    if (directionsNode != null) {
                        NodeList directions = directChildren(directionsNode, "direction");
                        for (int j = 0; j < directions.getLength(); j++) {
                            Node direction = directions.item(j);
                            Node idAttr = direction.getAttributes().getNamedItem("id");
                            if (idAttr != null && idAttr.getNodeValue().equals(String.valueOf(renderDirection))) {
                                Node directionAnimationsNode = firstDirectChild(direction, "animations");
                                if (directionAnimationsNode != null) {
                                    NodeList anims = directChildren(directionAnimationsNode, "animation");
                                    for (int k = 0; k < anims.getLength(); k++) {
                                        directionAnimations.add(anims.item(k));
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (!globalAnimations.isEmpty()) {
                return new NodeListWrapper(globalAnimations);
            }
            return directionAnimations.isEmpty() ? null : new NodeListWrapper(directionAnimations);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private static Node firstDirectChild(Node parent, String name) {
        if (parent == null) {
            return null;
        }
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && child.getNodeName().equals(name)) {
                return child;
            }
        }
        return null;
    }

    private static NodeList directChildren(Node parent, String name) {
        List<Node> nodes = new ArrayList<>();
        if (parent != null) {
            NodeList children = parent.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child.getNodeType() == Node.ELEMENT_NODE && child.getNodeName().equals(name)) {
                    nodes.add(child);
                }
            }
        }
        return new NodeListWrapper(nodes);
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
            
            chromaAsset.setImageX(chromaAsset.getImageX() + (drawingCanvas.getWidth() / 2));
            chromaAsset.setImageY(chromaAsset.getImageY() + (drawingCanvas.getHeight() / 2));
        }
        
        if (chromaAsset.getImageName().contains("_sd_")) {
            chromaAsset.setShadow(true);
            chromaAsset.setZ(Integer.MIN_VALUE);
        } else {
            chromaAsset.setZ(chromaAsset.getZ() + chromaAsset.getLayer());
        }
    }

    private List<ChromaAsset> createBuildQueue(int animationFrameIndex) {
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
                    int index = Math.floorMod(animationFrameIndex, frameData.getFrames().size());
                    frame = Integer.parseInt(frameData.getFrames().get(index));
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
        return renderImage(createFrameImage(0));
    }

    public byte[] createGif() {
        int frameCount = getAnimationFrameCount();
        List<BufferedImage> frames = new ArrayList<>();
        for (int i = 0; i < frameCount; i++) {
            frames.add(createFrameImage(i));
        }
        return SimpleGifEncoder.encode(frames, 120);
    }

    public int getAnimationFrameCount() {
        int frameCount = 1;
        for (ChromaAnimation animation : animations.values()) {
            ChromaFrame frame = animation.getStates().get(renderState);
            if (frame != null && !frame.getFrames().isEmpty()) {
                frameCount = Math.max(frameCount, frame.getFrames().size());
            }
        }
        return frameCount;
    }

    private BufferedImage createFrameImage(int animationFrameIndex) {
        List<ChromaAsset> buildQueue = createBuildQueue(animationFrameIndex);
        
        if (buildQueue == null) {
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
            if (imagePath == null) {
                throw new RuntimeException("Image path not found for asset: " + asset.getImageName());
            }
            
            try {
                BufferedImage image = ImageIO.read(new File(imagePath));
                if (image == null) {
                    throw new IOException("Unsupported image: " + imagePath);
                }
                
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
                    applyAddPinBlending(canvas, image, x, y, isTransparentCanvas());
                } else {
                    applyNormalBlending(canvas, image, x, y);
                }
                
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        
        g.dispose();
        
        BufferedImage finalImage = canvas;
        
        if (cropImage && !cropColours.isEmpty()) {
            finalImage = ImageUtil.trimBitmap(canvas, cropColours.toArray(new Color[0]));
        }

        return finalImage;
    }

    private byte[] renderImage(BufferedImage image) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private List<ChromaAsset> selectFallbackDirection(List<ChromaAsset> candidates, int requestedDirection) {
        List<ChromaAsset> validDirections = candidates.stream()
            .filter(x -> x.getDirection() == requestedDirection)
            .collect(Collectors.toList());

        if (!validDirections.isEmpty()) {
            return validDirections;
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

    private boolean isTransparentCanvas() {
        return !this.renderBackground && hexToColor(this.renderCanvasColour).getAlpha() == 0;
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
    private void applyAddPinBlending(BufferedImage canvas, BufferedImage foreground, int x, int y, boolean preserveDestinationAlpha) {
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
                
                int alpha;
                int r;
                int g;
                int b;
                if (preserveDestinationAlpha) {
                    alpha = bgColor.getAlpha();
                    if (alpha == 0) {
                        continue;
                    }

                    r = clampChannel(bgColor.getRed() + fgColor.getRed());
                    g = clampChannel(bgColor.getGreen() + fgColor.getGreen());
                    b = clampChannel(bgColor.getBlue() + fgColor.getBlue());
                } else {
                    alpha = blendNormalAlpha(fgColor.getAlpha(), bgColor.getAlpha());
                    r = blendAddChannel(fgColor.getRed(), fgColor.getAlpha(), bgColor.getRed(), bgColor.getAlpha(), alpha);
                    g = blendAddChannel(fgColor.getGreen(), fgColor.getAlpha(), bgColor.getGreen(), bgColor.getAlpha(), alpha);
                    b = blendAddChannel(fgColor.getBlue(), fgColor.getAlpha(), bgColor.getBlue(), bgColor.getAlpha(), alpha);
                }
                
                Color blendedColor = new Color(r, g, b, alpha);
                canvas.setRGB(cx, cy, blendedColor.getRGB());
            }
        }
    }

    private int blendAddChannel(int fg, int fgAlpha, int bg, int bgAlpha, int alpha) {
        if (alpha == 0) {
            return 0;
        }
        double sourceAlpha = fgAlpha / 255.0;
        double backgroundAlpha = bgAlpha / 255.0;
        double outAlpha = alpha / 255.0;
        double blended = Math.min(255, fg + bg);
        double blendWeight = backgroundAlpha * sourceAlpha;
        double backgroundWeight = backgroundAlpha - blendWeight;
        double sourceWeight = sourceAlpha - blendWeight;
        return clampChannel((int) Math.round((bg * backgroundWeight + fg * sourceWeight + blended * blendWeight) / outAlpha));
    }

    private BufferedImage copyImage(BufferedImage source) {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return copy;
    }

    public static Color hexToColor(String hexString) {
        if (hexString == null) {
            throw new NullPointerException("hexString");
        }

        if ("transparent".equalsIgnoreCase(hexString)) {
            return new Color(0, 0, 0, 0);
        }
        
        try {
            if (hexString.length() == 3) {
                int r = Integer.parseInt(hexString.substring(0, 1) + hexString.substring(0, 1), 16);
                int g = Integer.parseInt(hexString.substring(1, 2) + hexString.substring(1, 2), 16);
                int b = Integer.parseInt(hexString.substring(2, 3) + hexString.substring(2, 3), 16);
                return new Color(r, g, b);
            }
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
        
        return name + ".png";
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
