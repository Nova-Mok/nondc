import gearth.extensions.ExtensionForm;
import gearth.extensions.ExtensionInfo;
import gearth.extensions.parsers.*;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.control.*;
import org.jnativehook.GlobalScreen;
import org.jnativehook.NativeHookException;
import org.jnativehook.keyboard.NativeKeyEvent;
import org.jnativehook.keyboard.NativeKeyListener;
import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.LogManager;

@ExtensionInfo(
        Title = "Ultimate Non-DC",
        Description = "Only for noobs",
        Version = "1.1.2",
        Author = "Novamok"
)
public class GFootBall extends ExtensionForm implements NativeKeyListener {

    // UI Controls – coordinate displays are TextField to match FXML
    public TextField txtBallId;
    public RadioButton radioButtonShoot, radioButtonTrap, radioButtonDribble,
            radioButtonDoubleClick, radioButtonMix, radioButtonWalk, radioButtonRun;
    public CheckBox checkUserName, checkBall, checkDisableDouble, checkClickThrough, checkGuideTile,
            checkHideBubble, checkGuideTrap, checkDiagoKiller;
    public TextField textUserIndex, textUserCoords, textBallCoords;
    public TextField txtShoot, txtTrap, txtDribble, txtDoubleClick, txtMix, txtUniqueId;
    public TextField txtUpperLeft, txtUpperRight, txtLowerLeft, txtLowerRight;
    public Label labelShoot; // Used to reset focus

    // Internal state
    public String userName;
    public int currentX, currentY, ballX, ballY;
    public int clickX, clickY;
    public int userIndex = -1;
    public int userIdSelected = -1;

    private final Map<Integer, Integer> hashUserIdAndIndex = new HashMap<>();
    private final Map<Integer, String> hashUserIdAndName = new HashMap<>();
    // Concurrent map for thread-safe updates
    private final Map<Integer, HPoint> userCoords = new ConcurrentHashMap<>();

    private boolean flagBallTrap = false, flagBallDribble = false, guideTrap = false;
    
    // Shooting flag to temporarily disable double click blocking
    private boolean isShooting = false;

    // Mapping of key fields to actions for hotkeys
    private final Map<TextInputControl, Runnable> hotkeyMapping = new HashMap<>();

    private final Set<Integer> ballTypeIds = new HashSet<>();
    private final Map<Integer, HPoint> ballLocations = new ConcurrentHashMap<>();

    @Override
    protected void onShow() {
        sendToServer(new HPacket("{out:InfoRetrieve}"));
        sendToServer(new HPacket("{out:AvatarExpression}{i:0}"));
        sendToServer(new HPacket("{out:GetHeightMap}"));

        fetchBallTypeIds();

        // Suppress verbose logging from native hook libraries
        LogManager.getLogManager().reset();

        try {
            GlobalScreen.registerNativeHook();
            System.out.println("Native hook enabled");
        } catch (NativeHookException ex) {
            System.err.println("Error registering native hook: " + ex.getMessage());
            System.exit(1);
        }
        GlobalScreen.addNativeKeyListener(this);
        Platform.runLater(this::initHotkeys);
    }

    @Override
    protected void onHide() {
        userIndex = -1;
        sendToClient(new HPacket("ObjectRemove", HMessage.Direction.TOCLIENT, "1", false, 8636337, 0));
        sendToClient(new HPacket("{in:ObjectRemove}{s:\"2\"}{b:false}{i:8636337}{i:0}"));

        Platform.runLater(() -> {
            checkGuideTile.setSelected(false);
            checkGuideTrap.setSelected(false);
        });

        try {
            GlobalScreen.unregisterNativeHook();
            System.out.println("Native hook disabled");
        } catch (NativeHookException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void initExtension() {
        // Handle InfoRetrieve: get user name
        intercept(HMessage.Direction.TOCLIENT, "UserObject", hMessage -> {
            HPacket packet = hMessage.getPacket();
            int yourID = packet.readInteger();
            userName = packet.readString();
            Platform.runLater(() -> checkUserName.setText("User Name: " + userName));
        });

        // Handle Expression: get user index
        intercept(HMessage.Direction.TOCLIENT, "Expression", hMessage -> {
            if (primaryStage.isShowing() && userIndex == -1) {
                userIndex = hMessage.getPacket().readInteger();
                Platform.runLater(() -> textUserIndex.setText("User Index: " + userIndex));
            }
        });

        // Block StartTyping if hide bubble is selected
        intercept(HMessage.Direction.TOSERVER, "StartTyping", hMessage -> {
            if (primaryStage.isShowing() && checkHideBubble.isSelected()) {
                hMessage.setBlocked(true);
            }
        });

        intercept(HMessage.Direction.TOCLIENT, "RoomReady", hMessage -> {
            System.out.println("RoomReady");
            hashUserIdAndIndex.clear();
            hashUserIdAndName.clear();
        });

        // Update selected badges and user info
        intercept(HMessage.Direction.TOSERVER, "GetSelectedBadges", hMessage -> {
            try {
                userIdSelected = hMessage.getPacket().readInteger();
                if (checkUserName.isSelected()) {
                    userIndex = hashUserIdAndIndex.get(userIdSelected);
                    userName = hashUserIdAndName.get(userIdSelected);
                    Platform.runLater(() -> {
                        textUserIndex.setText("User Index: " + userIndex);
                        checkUserName.setText("User Name: " + userName);
                        checkUserName.setSelected(false);
                    });
                }
            } catch (NullPointerException ignored) { }
        });

        // Update user list upon room join
        intercept(HMessage.Direction.TOCLIENT, "Users", hMessage -> {
            try {
                HEntity[] roomUsersList = HEntity.parse(hMessage.getPacket());
                for (HEntity entity : roomUsersList) {
                    hashUserIdAndIndex.put(entity.getId(), entity.getIndex());
                    hashUserIdAndName.put(entity.getId(), entity.getName());
                }
            } catch (NullPointerException ignored) { }
            if (checkClickThrough.isSelected()) {
                sendToClient(new HPacket("{in:YouArePlayingGame}{b:true}"));
            }
        });

        // Update user coordinates and process ball actions during movement
        intercept(HMessage.Direction.TOCLIENT, "UserUpdate", hMessage -> {
            HPacket packet = hMessage.getPacket();
            for (HEntityUpdate update : HEntityUpdate.parse(packet)) {
                try {
                    int currentIndex = update.getIndex();
                    if (update.getMovingTo() != null) {
                        userCoords.put(currentIndex, update.getMovingTo());
                    }
                    if (currentIndex == userIndex) {
                        currentX = update.getMovingTo().getX();
                        currentY = update.getMovingTo().getY();
                        Platform.runLater(() -> textUserCoords.setText("User Coords: (" + currentX + ", " + currentY + ")"));

                        // Guide trap logic
                        int jokerX = update.getTile().getX();
                        int jokerY = update.getTile().getY();
                        if (checkGuideTrap.isSelected()) {
                            guideTrap = (jokerX == ballX && jokerY == ballY);
                            if (guideTrap) {
                                sendToClient(new HPacket("{in:Chat}{i:-1}{s:\"You are on the ball\"}{i:0}{i:30}{i:0}{i:0}"));
                            }
                        }

                        HPoint nearest = getNearestBall();
                        if (nearest != null) {
                            ballX = nearest.getX();
                            ballY = nearest.getY();
                            Platform.runLater(() -> textBallCoords.setText("Ball Coords: (" + ballX + ", " + ballY + ")"));
                        }

                        // Process trap action if flag set
                        if (flagBallTrap) {
                            if (ballX - 1 == currentX && ballY - 1 == currentY) { kickBall(1, 1); }
                            else if (ballX + 1 == currentX && ballY - 1 == currentY) { kickBall(-1, 1); }
                            else if (ballX - 1 == currentX && ballY + 1 == currentY) { kickBall(1, -1); }
                            else if (ballX + 1 == currentX && ballY + 1 == currentY) { kickBall(-1, -1); }
                        }
                        // Process dribble action if flag set
                        if (flagBallDribble) {
                            if (ballX - 1 == currentX && ballY - 1 == currentY) { kickBall(2, 2); }
                            else if (ballX + 1 == currentX && ballY - 1 == currentY) { kickBall(-2, 2); }
                            else if (ballX - 1 == currentX && ballY + 1 == currentY) { kickBall(2, -2); }
                            else if (ballX + 1 == currentX && ballY + 1 == currentY) { kickBall(-2, -2); }
                        }
                    }
                } catch (NullPointerException ignored) { }
            }
        });

        // Suggest move when guide trap is active (block MoveAvatar packet)
        intercept(HMessage.Direction.TOSERVER, "MoveAvatar", hMessage -> {
            if (guideTrap) {
                clickX = hMessage.getPacket().readInteger();
                clickY = hMessage.getPacket().readInteger();
                Suggest(clickX, clickY);
                hMessage.setBlocked(true);
            }
        });

        // Update ball coordinates on object update
        intercept(HMessage.Direction.TOCLIENT, "ObjectUpdate", hMessage -> {
            try {
                int furnitureId = hMessage.getPacket().readInteger();

                if (ballLocations.containsKey(furnitureId)) {
                    hMessage.getPacket().readInteger(); // Skip UniqueId
                    int newX = hMessage.getPacket().readInteger();
                    int newY = hMessage.getPacket().readInteger();
                    hMessage.getPacket().readInteger(); // direction
                    String zTile = hMessage.getPacket().readString();
                    ballLocations.put(furnitureId, new HPoint(newX, newY));
                    System.out.println("Updated ball id " + furnitureId + " at (" + newX + "," + newY + ")");
                }
                else if (furnitureId == Integer.parseInt(txtBallId.getText())) {
                    hMessage.getPacket().readInteger(); // Skip UniqueId
                    ballX = hMessage.getPacket().readInteger();
                    ballY = hMessage.getPacket().readInteger();
                    hMessage.getPacket().readInteger(); // direction
                    String zTile = hMessage.getPacket().readString();
                    Platform.runLater(() -> textBallCoords.setText("Ball Coords: (" + ballX + ", " + ballY + ")"));
                    if (checkDiagoKiller.isSelected()) {
                        tileInClient(zTile);
                    }
                }
            } catch (Exception ignored) { }
        });

        // Handle sliding ball update
        intercept(HMessage.Direction.TOCLIENT, "SlideObjectBundle", hMessage -> {
            try {
                int oldX = hMessage.getPacket().readInteger();
                int oldY = hMessage.getPacket().readInteger();
                int newX = hMessage.getPacket().readInteger();
                int newY = hMessage.getPacket().readInteger();
                hMessage.getPacket().readInteger(); // direction
                int furnitureId = hMessage.getPacket().readInteger();
                String zTile = hMessage.getPacket().readString();
                if (ballLocations.containsKey(furnitureId)) {
                    ballLocations.put(furnitureId, new HPoint(newX, newY));
                    System.out.println("Updated ball id " + furnitureId + " at (" + newX + "," + newY + ")");
                }
                if (furnitureId == Integer.parseInt(txtBallId.getText())) {
                    ballX = newX;
                    ballY = newY;
                    Platform.runLater(() -> textBallCoords.setText("Ball Coords: (" + ballX + ", " + ballY + ")"));
                    if (checkDiagoKiller.isSelected()) {
                        tileInClient(zTile);
                    }
                }
            } catch (Exception ignored) { }
        });

        // Capture ball id on double-click, if ball selection is active.
        // When disableDouble is checked and we are not in shooting mode, block the double-click.
        intercept(HMessage.Direction.TOSERVER, "UseFurniture", hMessage -> {
            if (checkDisableDouble.isSelected() && !isShooting) {
                hMessage.setBlocked(true);
            } else if (checkBall.isSelected() && !checkDisableDouble.isSelected()) {
                int ballID = hMessage.getPacket().readInteger();
                Platform.runLater(() -> txtBallId.setText(String.valueOf(ballID)));
                checkBall.setSelected(false);
            }
        });
        
        intercept(HMessage.Direction.TOCLIENT, "Objects", hMessage -> {
            try {
                HFloorItem[] floorItems = HFloorItem.parse(hMessage.getPacket());
                for (HFloorItem item : floorItems) {
                    if (ballTypeIds.contains(item.getTypeId())) {
                        HPoint pos = new HPoint(item.getTile().getX(), item.getTile().getY());
                        ballLocations.put(item.getId(), pos);
                        System.out.println("Found ball id " + item.getId() + " at " + pos);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    // Display guide tiles in the client based on the zTile value
    private void tileInClient(String zTile) {
        int[][] offsets = {
                {-4, 4}, {4, 4}, {-4, -4}, {4, -4},
                {-4, 0}, {4, 0}, {0, -4}, {0, 4}
        };
        for (int i = 0; i < offsets.length; i++) {
            String packet = String.format(
                    "{in:ObjectUpdate}{i:%d}{i:%s}{i:%d}{i:%d}{i:0}{s:\"%s\"}{s:\"0.0\"}{i:0}{i:0}{s:\"0\"}{i:-1}{i:1}{i:123}",
                    i + 3, txtUniqueId.getText(), ballX + offsets[i][0], ballY + offsets[i][1], zTile);
            sendToClient(new HPacket(packet));
        }
    }

    // Kick the ball: update client and server positions
    public void kickBall(int plusX, int plusY) {
        sendToClient(new HPacket("ObjectUpdate", HMessage.Direction.TOCLIENT,
                1, 8237, ballX + plusX, ballY + plusY, 0, "0.0", "1.0", 0, 0, 1, 822083583, 2, userName));
        sendToServer(new HPacket(String.format("{out:MoveAvatar}{i:%d}{i:%d}", ballX + plusX, ballY + plusY)));
        flagBallTrap = false;
    }

    // Suggest a move based on click position relative to the ball
    public void Suggest(int clickX, int clickY) {
        if (clickX == ballX - 1 && clickY == ballY - 1) {
            sendSuggestion(ballX + 6, ballY + 6);
        } else if (clickX == ballX + 1 && clickY == ballY + 1) {
            sendSuggestion(ballX - 6, ballY - 6);
        } else if (clickX == ballX - 1 && clickY == ballY + 1) {
            sendSuggestion(ballX + 6, ballY - 6);
        } else if (clickX == ballX + 1 && clickY == ballY - 1) {
            sendSuggestion(ballX - 6, ballY + 6);
        } else if (clickX == ballX - 1 && clickY == ballY) {
            sendSuggestion(ballX + 6, ballY);
        } else if (clickX == ballX + 1 && clickY == ballY) {
            sendSuggestion(ballX - 6, ballY);
        } else if (clickX == ballX && clickY == ballY + 1) {
            sendSuggestion(ballX, ballY - 6);
        } else if (clickX == ballX && clickY == ballY - 1) {
            sendSuggestion(ballX, ballY + 6);
        }
        sendToClient(new HPacket("{in:Chat}{i:-1}{s:\"Remember to press the ESCAPE key to kick\"}{i:0}{i:30}{i:0}{i:0}"));
    }

    private void sendSuggestion(int newX, int newY) {
        sendToClient(new HPacket("ObjectUpdate", HMessage.Direction.TOCLIENT,
                2, 8237, newX, newY, 0, "0.0", "1.0", 0, 0, 1, 822083583, 2, userName));
    }

    public void handleClickThrough() {
        boolean enable = checkClickThrough.isSelected();
        sendToClient(new HPacket(String.format("{in:YouArePlayingGame}{b:%s}", enable)));
    }

    // Native key listener methods
    @Override
    public void nativeKeyTyped(NativeKeyEvent nativeKeyEvent) { }

    @Override
    public void nativeKeyPressed(NativeKeyEvent nativeKeyEvent) {
        int keyCode = nativeKeyEvent.getKeyCode();
        String keyText = NativeKeyEvent.getKeyText(keyCode);

        // Cancel guide trap if ESC is pressed
        if (keyCode == NativeKeyEvent.VC_ESCAPE) {
            guideTrap = false;
            sendToServer(new HPacket(String.format("{out:MoveAvatar}{i:%d}{i:%d}", clickX, clickY)));
        }

        // Reset trap and dribble flags if these modes are active
        if (radioButtonTrap.isSelected() || radioButtonDribble.isSelected()) {
            flagBallTrap = false;
            flagBallDribble = false;
        }

        boolean fieldFocused = false;
        TextInputControl[] hotkeyFields = {txtShoot, txtTrap, txtDribble, txtDoubleClick, txtMix,
                txtUpperLeft, txtUpperRight, txtLowerLeft, txtLowerRight};
        for (TextInputControl field : hotkeyFields) {
            if (field.isFocused()) {
                fieldFocused = true;
                Platform.runLater(() -> {
                    field.setText(keyText);
                    if (field.equals(txtShoot)) {
                        radioButtonShoot.setText(String.format("Shoot [Key %s]", keyText));
                    } else if (field.equals(txtTrap)) {
                        radioButtonTrap.setText(String.format("Trap [Key %s]", keyText));
                    } else if (field.equals(txtDribble)) {
                        radioButtonDribble.setText(String.format("Dribble [Key %s]", keyText));
                    } else if (field.equals(txtDoubleClick)) {
                        radioButtonDoubleClick.setText(String.format("DoubleClick [Key %s]", keyText));
                    } else if (field.equals(txtMix)) {
                        radioButtonMix.setText(String.format("Mix (Trap & Dribble) [Key %s]", keyText));
                    }
                });
                Platform.runLater(labelShoot::requestFocus);
                break;
            }
        }

        if (!fieldFocused) {
            hotkeyMapping.forEach((field, action) -> {
                if (keyText.equals(field.getText())) {
                    action.run();
                }
            });
        }
    }

    @Override
    public void nativeKeyReleased(NativeKeyEvent nativeKeyEvent) { }

    // Initialize the mapping between key fields and their actions
    private void initHotkeys() {
        hotkeyMapping.put(txtShoot, this::keyShoot);
        hotkeyMapping.put(txtTrap, this::keyTrap);
        hotkeyMapping.put(txtDribble, this::keyDribble);
        hotkeyMapping.put(txtDoubleClick, this::keyDoubleClick);
        hotkeyMapping.put(txtMix, this::keyMix);
        hotkeyMapping.put(txtUpperLeft, this::keyUpperLeft);
        hotkeyMapping.put(txtUpperRight, this::keyUpperRight);
        hotkeyMapping.put(txtLowerLeft, this::keyLowerLeft);
        hotkeyMapping.put(txtLowerRight, this::keyLowerRight);
    }

    // Helper method to get nearest ball based on current user coordinates
    private HPoint getNearestBall() {
        HPoint nearest = null;
        double minDistance = Double.MAX_VALUE;
        for (HPoint pos : ballLocations.values()) {
            double distance = Math.sqrt(Math.pow(pos.getX() - currentX, 2) + Math.pow(pos.getY() - currentY, 2));
            if (distance < minDistance) {
                minDistance = distance;
                nearest = pos;
            }
        }
        return nearest;
    }

    // Action methods

    private void keyUpperLeft() {
        sendToServer(new HPacket(String.format("{out:MoveAvatar}{i:%d}{i:%d}", ballX - 3, ballY - 3)));
    }

    private void keyUpperRight() {
        sendToServer(new HPacket(String.format("{out:MoveAvatar}{i:%d}{i:%d}", ballX + 3, ballY - 3)));
    }

    private void keyLowerLeft() {
        sendToServer(new HPacket(String.format("{out:MoveAvatar}{i:%d}{i:%d}", ballX - 3, ballY + 3)));
    }

    private void keyLowerRight() {
        sendToServer(new HPacket(String.format("{out:MoveAvatar}{i:%d}{i:%d}", ballX + 3, ballY + 3)));
    }

    private void keyDoubleClick() {
        Platform.runLater(() -> radioButtonDoubleClick.setSelected(true));
        sendToServer(new HPacket(String.format("{out:UseFurniture}{i:%d}{i:0}", Integer.parseInt(txtBallId.getText()))));
        sendToClient(new HPacket("ObjectUpdate", HMessage.Direction.TOCLIENT,
                1, 8237, ballX, ballY, 0, "0.0", "1.0", 0, 0, 1, 822083583, 2, userName));
    }

    private void keyShoot() {
        Platform.runLater(() -> radioButtonShoot.setSelected(true));
        HPoint nearestBall = getNearestBall();
        if (nearestBall != null) {
            ballX = nearestBall.getX();
            ballY = nearestBall.getY();
        } else {
            System.out.println("No ball found to shoot.");
            return;
        }
        sendToClient(new HPacket("ObjectUpdate", HMessage.Direction.TOCLIENT,
                1, 8237, ballX, ballY, 0, "0.0", "1.0", 0, 0, 1, 822083583, 2, userName));
        sendToServer(new HPacket(String.format("{out:MoveAvatar}{i:%d}{i:%d}", ballX, ballY)));
    }

    private void keyDribble() {
        Platform.runLater(() -> radioButtonDribble.setSelected(true));
        if (ballX == currentX && ballY > currentY) { 
            kickBall(0, 2); 
        } else if (ballX == currentX && ballY < currentY) { 
            kickBall(0, -2); 
        } else if (ballX > currentX && ballY == currentY) { 
            kickBall(2, 0); 
        } else if (ballX < currentX && ballY == currentY) { 
            kickBall(-2, 0); 
        } else if (ballX > currentX && ballY > currentY) {
            if (ballX - 1 == currentX && ballY - 1 == currentY) { 
                kickBall(2, 2); 
            } else {
                sendAndMove(ballX - 1, ballY - 1);
                flagBallDribble = true;
            }
        } else if (ballX < currentX && ballY > currentY) {
            if (ballX + 1 == currentX && ballY - 1 == currentY) { 
                kickBall(-2, 2); 
            } else {
                sendAndMove(ballX + 1, ballY - 1);
                flagBallDribble = true;
            }
        } else if (ballX > currentX && ballY < currentY) {
            if (ballX - 1 == currentX && ballY + 1 == currentY) { 
                kickBall(2, -2); 
            } else {
                sendAndMove(ballX - 1, ballY + 1);
                flagBallDribble = true;
            }
        } else if (ballX < currentX && ballY < currentY) {
            if (ballX + 1 == currentX && ballY + 1 == currentY) { 
                kickBall(-2, -2); 
            } else {
                sendAndMove(ballX + 1, ballY + 1);
                flagBallDribble = true;
            }
        }
    }

    private void keyMix() {
        Platform.runLater(() -> radioButtonMix.setSelected(true));
        int deltaX = currentX - ballX;
        int deltaY = currentY - ballY;
        int directionX = 0, directionY = 0, steps = 1;

        if (Math.abs(deltaX) == Math.abs(deltaY)) {
            directionX = (deltaX > 0) ? -1 : 1;
            directionY = (deltaY > 0) ? -1 : 1;
        } else if (Math.abs(deltaX) > Math.abs(deltaY)) {
            directionX = -1;
        } else if (Math.abs(deltaY) > Math.abs(deltaX)) {
            directionY = (deltaY > 0) ? -1 : 1;
        } else {
            directionX = 1;
        }

        int targetX = ballX + directionX * steps;
        int targetY = ballY + directionY * steps;
        while (occupiedTile(targetX, targetY)) {
            steps++;
            targetX = ballX + directionX * steps;
            targetY = ballY + directionY * steps;
        }
        sendAndMove(targetX, targetY);
    }

    private void keyTrap() {
        Platform.runLater(() -> radioButtonTrap.setSelected(true));
        HPoint nearestBall = getNearestBall();
        if (nearestBall != null) {
            ballX = nearestBall.getX();
            ballY = nearestBall.getY();
        } else {
            System.out.println("No ball found to trap.");
            return;
        }
        if (ballX == currentX && ballY > currentY) {
            kickBall(0, 1);
        } else if (ballX == currentX && ballY < currentY) {
            kickBall(0, -1);
        } else if (ballX > currentX && ballY == currentY) {
            kickBall(1, 0);
        } else if (ballX < currentX && ballY == currentY) {
            kickBall(-1, 0);
        } else if (ballX > currentX && ballY > currentY) {
            if (ballX - 1 == currentX && ballY - 1 == currentY) {
                kickBall(1, 1);
            } else {
                sendAndMove(ballX - 1, ballY - 1);
                flagBallTrap = true;
            }
        } else if (ballX < currentX && ballY > currentY) {
            if (ballX + 1 == currentX && ballY - 1 == currentY) {
                kickBall(-1, 1);
            } else {
                sendAndMove(ballX + 1, ballY - 1);
                flagBallTrap = true;
            }
        } else if (ballX > currentX && ballY < currentY) {
            if (ballX - 1 == currentX && ballY + 1 == currentY) {
                kickBall(1, -1);
            } else {
                sendAndMove(ballX - 1, ballY + 1);
                flagBallTrap = true;
            }
        } else if (ballX < currentX && ballY < currentY) {
            if (ballX + 1 == currentX && ballY + 1 == currentY) {
                kickBall(-1, -1);
            } else {
                sendAndMove(ballX + 1, ballY + 1);
                flagBallTrap = true;
            }
        }
    }

    // Helper method to send update and move avatar
    private void sendAndMove(int x, int y) {
        sendToClient(new HPacket("ObjectUpdate", HMessage.Direction.TOCLIENT,
                1, 8237, x, y, 0, "0.0", "1.0", 0, 0, 1, 822083583, 2, userName));
        sendToServer(new HPacket(String.format("{out:MoveAvatar}{i:%d}{i:%d}", x, y)));
    }

    // Check if a tile is occupied by any user
    private boolean occupiedTile(int x, int y) {
        return userCoords.values().stream().anyMatch(point -> point.getX() == x && point.getY() == y);
    }

    // UI button handlers for guide tiles/traps
    public void handleGuideTile() {
        if (checkGuideTile.isSelected()) {
            sendToClient(new HPacket("ObjectAdd", HMessage.Direction.TOCLIENT, 1,
                    Integer.parseInt(txtUniqueId.getText()), 0, 0, 0, "0.0", "0.2", 0, 0, "1", -1, 1, 2, userName));
        } else {
            sendToClient(new HPacket("{in:ObjectRemove}{i:\"1\"}{b:false}{i:8636337}{i:0}"));
        }
    }

    public void handleGuideTrap() {
        if (checkGuideTrap.isSelected()) {
            sendToClient(new HPacket("ObjectAdd", HMessage.Direction.TOCLIENT, 2,
                    Integer.parseInt(txtUniqueId.getText()), 0, 0, 0, "0.0", "0.2", 0, 0, "1", -1, 1, 2, userName));
        } else {
            sendToClient(new HPacket("{in:ObjectRemove}{i:\"2\"}{b:false}{i:8636337}{i:0}"));
        }
    }

    public void handleDiagoKiller(ActionEvent actionEvent) {
        CheckBox chkBoxDiago = (CheckBox) actionEvent.getSource();
        if (chkBoxDiago.isSelected()) {
            String[] diagPositions = {
                    "{in:ObjectAdd}{i:3}{i:%s}{i:-4}{i:4}{i:0}{s:\"0.5\"}{s:\"0.0\"}{i:0}{i:0}{s:\"0\"}{i:-1}{i:0}{i:123}{s:\"OwnerName\"}",
                    "{in:ObjectAdd}{i:4}{i:%s}{i:4}{i:4}{i:0}{s:\"0.5\"}{s:\"0.0\"}{i:0}{i:0}{s:\"0\"}{i:-1}{i:0}{i:123}{s:\"OwnerName\"}",
                    "{in:ObjectAdd}{i:5}{i:%s}{i:-4}{i:-4}{i:0}{s:\"0.5\"}{s:\"0.0\"}{i:0}{i:0}{s:\"0\"}{i:-1}{i:0}{i:123}{s:\"OwnerName\"}",
                    "{in:ObjectAdd}{i:6}{i:%s}{i:4}{i:-4}{i:0}{s:\"0.5\"}{s:\"0.0\"}{i:0}{i:0}{s:\"0\"}{i:-1}{i:0}{i:123}{s:\"OwnerName\"}",
                    "{in:ObjectAdd}{i:7}{i:%s}{i:-4}{i:0}{i:0}{s:\"0.5\"}{s:\"0.0\"}{i:0}{i:0}{s:\"0\"}{i:-1}{i:0}{i:123}{s:\"OwnerName\"}",
                    "{in:ObjectAdd}{i:8}{i:%s}{i:4}{i:0}{i:0}{s:\"0.5\"}{s:\"0.0\"}{i:0}{i:0}{s:\"0\"}{i:-1}{i:0}{i:123}{s:\"OwnerName\"}",
                    "{in:ObjectAdd}{i:9}{i:%s}{i:0}{i:-4}{i:0}{s:\"0.5\"}{s:\"0.0\"}{i:0}{i:0}{s:\"0\"}{i:-1}{i:0}{i:123}{s:\"OwnerName\"}",
                    "{in:ObjectAdd}{i:10}{i:%s}{i:0}{i:4}{i:0}{s:\"0.5\"}{s:\"0.0\"}{i:0}{i:0}{s:\"0\"}{i:-1}{i:0}{i:123}{s:\"OwnerName\"}"
            };
            for (String diag : diagPositions) {
                sendToClient(new HPacket(String.format(diag, txtUniqueId.getText())));
            }
        } else {
            for (int i = 3; i <= 10; i++) {
                sendToClient(new HPacket(String.format("{in:ObjectRemove}{s:\"%d\"}{b:false}{i:123}{i:0}", i)));
            }
        }
    }

    // Fetch ball type IDs from the furnidata API and store those with classnames fball_ball, fball_ball5, fball_ball6
    private void fetchBallTypeIds() {
        new Thread(() -> {
            try {
                String url = "https://www.habbo.com/gamedata/furnidata_json/1";
                URLConnection connection = new URL(url).openConnection();
                connection.setRequestProperty("User-Agent", "Mozilla/5.0");
                connection.connect();
                String jsonText = IOUtils.toString(connection.getInputStream(), StandardCharsets.UTF_8);
                JSONObject jsonObj = new JSONObject(jsonText);
                JSONArray furnitypeArray = jsonObj
                        .getJSONObject("roomitemtypes")
                        .getJSONArray("furnitype");

                for (int i = 0; i < furnitypeArray.length(); i++) {
                    JSONObject item = furnitypeArray.getJSONObject(i);
                    String classname = item.getString("classname");
                    if (classname.equals("fball_ball") ||
                        classname.equals("fball_ball5") ||
                        classname.equals("fball_ball6")) {
                        int id = item.getInt("id");
                        ballTypeIds.add(id);
                    }
                }
                System.out.println("Ball Type IDs fetched: " + ballTypeIds);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }
}
