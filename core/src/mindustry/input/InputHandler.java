package mindustry.input;

import arc.*;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.input.*;
import arc.input.GestureDetector.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.ArcAnnotate.*;
import arc.util.*;
import mindustry.annotations.Annotations.*;
import mindustry.client.*;
import mindustry.client.antigreif.*;
import mindustry.client.pathfinding.*;
import mindustry.content.*;
import mindustry.entities.*;
import mindustry.entities.effect.*;
import mindustry.entities.traits.BuilderTrait.*;
import mindustry.entities.type.*;
import mindustry.game.EventType.*;
import mindustry.game.*;
import mindustry.game.Teams.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.input.Placement.*;
import mindustry.net.*;
import mindustry.net.Administration.*;
import mindustry.type.*;
import mindustry.ui.fragments.*;
import mindustry.world.*;
import mindustry.world.blocks.*;
import mindustry.world.blocks.BuildBlock.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.power.*;

import java.util.*;

import static arc.Core.camera;
import static mindustry.Vars.*;

public abstract class InputHandler implements InputProcessor, GestureListener{
    /** Used for dropping items. */
    final static float playerSelectRange = mobile ? 17f : 11f;
    /** Maximum line length. */
    final static int maxLength = 100;
    final static Vec2 stackTrns = new Vec2();
    final static Rect r1 = new Rect(), r2 = new Rect();
    /** Distance on the back from where items originate. */
    final static float backTrns = 3f;

    public final OverlayFragment frag = new OverlayFragment();

    public Block block;
    public boolean overrideLineRotation;
    public int rotation;
    public boolean droppingItem;
    public Group uiGroup;

    protected @Nullable Schematic lastSchematic;
    protected GestureDetector detector;
    protected PlaceLine line = new PlaceLine();
    protected BuildRequest resultreq;
    protected BuildRequest brequest = new BuildRequest();
    protected Array<BuildRequest> lineRequests = new Array<>();
    protected Array<BuildRequest> selectRequests = new Array<>();

    //methods to override

    @Remote(variants = Variant.one)
    public static void removeQueueBlock(int x, int y, boolean breaking){
        player.removeRequest(x, y, breaking);
    }

    @Remote(targets = Loc.client, called = Loc.server)
    public static void dropItem(Player player, float angle){
        if(net.server() && player.item().amount <= 0){
            throw new ValidateException(player, "Player cannot drop an item.");
        }

        Effects.effect(Fx.dropItem, Color.white, player.x, player.y, angle, player.item().item);
        player.clearItem();
    }

    @Remote(targets = Loc.both, called = Loc.server, forward = true, unreliable = true)
    public static void rotateBlock(Player player, Tile tile, boolean direction){
        if(net.server() && (!Units.canInteract(player, tile) ||
            !netServer.admins.allowAction(player, ActionType.rotate, tile, action -> action.rotation = Mathf.mod(tile.rotation() + Mathf.sign(direction), 4)))){
            throw new ValidateException(player, "Player cannot rotate a block.");
        }

        tile.rotation(Mathf.mod(tile.rotation() + Mathf.sign(direction), 4));

        if(tile.entity != null){
            tile.entity.updateProximity();
            tile.entity.noSleep();
        }
    }

    @Remote(targets = Loc.both, forward = true, called = Loc.server)
    public static void transferInventory(Player player, Tile tile){
        if(player == null || player.timer == null) return;
        if(net.server() && (player.item().amount <= 0 || player.isTransferring|| !Units.canInteract(player, tile) ||
            !netServer.admins.allowAction(player, ActionType.depositItem, tile, action -> {
                action.itemAmount = player.item().amount;
                action.item = player.item().item;
            }))){
            throw new ValidateException(player, "Player cannot transfer an item.");
        }

        if(tile.entity == null) return;

        player.isTransferring = true;

        Item item = player.item().item;
        int amount = player.item().amount;
        int accepted = tile.block().acceptStack(item, amount, tile, player);
        player.item().amount -= accepted;

        int sent = Mathf.clamp(accepted / 4, 1, 8);
        int removed = accepted / sent;
        int[] remaining = {accepted, accepted};
        Block block = tile.block();

        Core.app.post(() -> Events.fire(new DepositEvent(tile, player, item, accepted)));

        for(int i = 0; i < sent; i++){
            boolean end = i == sent - 1;
            Time.run(i * 3, () -> {
                tile.block().getStackOffset(item, tile, stackTrns);

                ItemTransfer.create(item,
                player.x + Angles.trnsx(player.rotation + 180f, backTrns), player.y + Angles.trnsy(player.rotation + 180f, backTrns),
                new Vec2(tile.drawx() + stackTrns.x, tile.drawy() + stackTrns.y), () -> {
                    if(tile.block() != block || tile.entity == null || tile.entity.items == null) return;

                    tile.block().handleStack(item, removed, tile, player);
                    remaining[1] -= removed;

                    if(end && remaining[1] > 0){
                        tile.block().handleStack(item, remaining[1], tile, player);
                    }
                });

                remaining[0] -= removed;

                if(end){
                    player.isTransferring = false;
                }
            });
        }
    }

    @Remote(targets = Loc.both, called = Loc.server, forward = true)
    public static void onTileTapped(Player player, Tile tile){
        if(tile == null || player == null) return;
        if(net.server() && (!Units.canInteract(player, tile) ||
            !netServer.admins.allowAction(player, ActionType.tapTile, tile, action -> {}))) throw new ValidateException(player, "Player cannot tap a tile.");
        tile.block().tapped(tile, player);
        Core.app.post(() -> Events.fire(new TapEvent(tile, player)));
    }

    @Remote(targets = Loc.both, called = Loc.both, forward = true)
    public static void onTileConfig(Player player, Tile tile, int value){
        if(tile == null) return;
        if(player != null){
            player.log.add(new InteractionLogItem(new ConfigRequest(tile, value)));
            String powerNodeText = "";
            if(tile.numConnectionsRemoved != null && Time.millis() - tile.timeConnectionsRemoved < 1000){
                powerNodeText = String.format(", splitting the power grid (%d tiles affected)", tile.numConnectionsRemoved);
                if(Core.settings.getBool("powersplitnotifications")){
                    ui.chatfrag.addMessage(String.format("%s [lightgray]split power by configuring at %d, %d (%d tiles affected)", player.name, tile.x, tile.y, tile.numConnectionsRemoved), "client");
                }
            }
            tile.log.add(new TileLogItem(TileLogType.Configured, player.name, String.format("%s at %d, %d", tile.block().name, tile.x, tile.y) + powerNodeText));
        }

        if(net.server() && (!Units.canInteract(player, tile) ||
            !netServer.admins.allowAction(player, ActionType.configure, tile, action -> action.config = value))) throw new ValidateException(player, "Player cannot configure a tile.");
        tile.block().configured(tile, player, value);
        Core.app.post(() -> Events.fire(new TapConfigEvent(tile, player, value)));
    }

    public Eachable<BuildRequest> allRequests(){
        return cons -> {
            for(BuildRequest request : player.buildQueue()) cons.get(request);
            for(BuildRequest request : selectRequests) cons.get(request);
            for(BuildRequest request : lineRequests) cons.get(request);
        };
    }

    public OverlayFragment getFrag(){
        return frag;
    }

    public void update(){

    }

    public float getMouseX(){
        return Core.input.mouseX();
    }

    public float getMouseY(){
        return Core.input.mouseY();
    }

    public void buildPlacementUI(Table table){

    }

    public void buildUI(Group group){

    }

    public void updateState(){

    }

    public void drawBottom(){

    }

    public void drawTop(){

    }

    public void drawSelected(int x, int y, Block block, Color color){
        Draw.color(color);
        for(int i = 0; i < 4; i++){
            Point2 p = Geometry.d8edge[i];
            float offset = -Math.max(block.size - 1, 0) / 2f * tilesize;
            Draw.rect("block-select",
                x*tilesize + block.offset() + offset * p.x,
                y*tilesize + block.offset() + offset * p.y, i * 90);
        }
        Draw.reset();
    }

    public void drawBreaking(BuildRequest request){
        if(request.breaking){
            drawBreaking(request.x, request.y);
        }else{
            drawSelected(request.x, request.y, request.block, Pal.remove);
        }
    }

    public boolean requestMatches(BuildRequest request){
        Tile tile = world.tile(request.x, request.y);
        return tile != null && tile.block() instanceof BuildBlock && tile.<BuildEntity>ent().cblock == request.block;
    }

    public void drawBreaking(int x, int y){
        Tile tile = world.ltile(x, y);
        if(tile == null) return;
        Block block = tile.block();

        drawSelected(x, y, block, Pal.remove);
    }

    public void useSchematic(Schematic schem){
        selectRequests.addAll(schematics.toRequests(schem, world.toTile(player.x), world.toTile(player.y)));
    }

    protected void showSchematicSave(){
        if(lastSchematic == null) return;

        ui.showTextInput("$schematic.add", "$name", "", text -> {
            Schematic replacement = schematics.all().find(s -> s.name().equals(text));
            if(replacement != null){
                ui.showConfirm("$confirm", "$schematic.replace", () -> {
                    schematics.overwrite(replacement, lastSchematic);
                    ui.showInfoFade("$schematic.saved");
                    ui.schematics.showInfo(replacement);
                });
            }else{
                lastSchematic.tags.put("name", text);
                schematics.add(lastSchematic);
                ui.showInfoFade("$schematic.saved");
                ui.schematics.showInfo(lastSchematic);
            }
        });
    }

    public void rotateRequests(Array<BuildRequest> requests, int direction){
        int ox = schemOriginX(), oy = schemOriginY();

        requests.each(req -> {
            //rotate config position
            if(req.block.posConfig){
                int cx = Pos.x(req.config) - req.originalX, cy = Pos.y(req.config) - req.originalY;
                int lx = cx;

                if(direction >= 0){
                    cx = -cy;
                    cy = lx;
                }else{
                    cx = cy;
                    cy = -lx;
                }
                req.config = Pos.get(cx + req.originalX, cy + req.originalY);
            }

            //rotate actual request, centered on its multiblock position
            float wx = (req.x - ox) * tilesize + req.block.offset(), wy = (req.y - oy) * tilesize + req.block.offset();
            float x = wx;
            if(direction >= 0){
                wx = -wy;
                wy = x;
            }else{
                wx = wy;
                wy = -x;
            }
            req.x = world.toTile(wx - req.block.offset()) + ox;
            req.y = world.toTile(wy - req.block.offset()) + oy;
            req.rotation = Mathf.mod(req.rotation + direction, 4);
        });
    }

    public void flipRequests(Array<BuildRequest> requests, boolean x){
        int origin = (x ? schemOriginX() : schemOriginY()) * tilesize;

        requests.each(req -> {
            float value = -((x ? req.x : req.y) * tilesize - origin + req.block.offset()) + origin;

            if(x){
                req.x = (int)((value - req.block.offset()) / tilesize);
            }else{
                req.y = (int)((value - req.block.offset()) / tilesize);
            }

            if(req.block.posConfig){
                int corigin = x ? req.originalWidth/2 : req.originalHeight/2;
                int nvalue = -((x ? Pos.x(req.config) : Pos.y(req.config)) - corigin) + corigin;
                if(x){
                    req.originalX = -(req.originalX - corigin) + corigin;
                    req.config = Pos.get(nvalue, Pos.y(req.config));
                }else{
                    req.originalY = -(req.originalY - corigin) + corigin;
                    req.config = Pos.get(Pos.x(req.config), nvalue);
                }
            }

            //flip rotation
            if(x == (req.rotation % 2 == 0)){
                req.rotation = Mathf.mod(req.rotation + 2, 4);
            }
        });
    }

    protected int schemOriginX(){
        return rawTileX();
    }

    protected int schemOriginY(){
        return rawTileY();
    }

    /** Returns the selection request that overlaps this position, or null. */
    protected BuildRequest getRequest(int x, int y){
        return getRequest(x, y, 1, null);
    }

    /** Returns the selection request that overlaps this position, or null. */
    protected BuildRequest getRequest(int x, int y, int size, BuildRequest skip){
        float offset = ((size + 1) % 2) * tilesize / 2f;
        r2.setSize(tilesize * size);
        r2.setCenter(x * tilesize + offset, y * tilesize + offset);
        resultreq = null;

        Boolf<BuildRequest> test = req -> {
            if(req == skip) return false;
            Tile other = req.tile();

            if(other == null) return false;

            if(!req.breaking){
                r1.setSize(req.block.size * tilesize);
                r1.setCenter(other.worldx() + req.block.offset(), other.worldy() + req.block.offset());
            }else{
                r1.setSize(other.block().size * tilesize);
                r1.setCenter(other.worldx() + other.block().offset(), other.worldy() + other.block().offset());
            }

            return r2.overlaps(r1);
        };

        for(BuildRequest req : player.buildQueue()){
            if(test.get(req)) return req;
        }

        for(BuildRequest req : selectRequests){
            if(test.get(req)) return req;
        }

        return null;
    }

    protected void drawBreakSelection(int x1, int y1, int x2, int y2){
        NormalizeDrawResult result = Placement.normalizeDrawArea(Blocks.air, x1, y1, x2, y2, false, maxLength, 1f);
        NormalizeResult dresult = Placement.normalizeArea(x1, y1, x2, y2, rotation, false, maxLength);

        for(int x = dresult.x; x <= dresult.x2; x++){
            for(int y = dresult.y; y <= dresult.y2; y++){
                Tile tile = world.ltile(x, y);
                if(tile == null || !validBreak(tile.x, tile.y)) continue;

                drawBreaking(tile.x, tile.y);
            }
        }

        Tmp.r1.set(result.x, result.y, result.x2 - result.x, result.y2 - result.y);

        Draw.color(Pal.remove);
        Lines.stroke(1f);

        for(BuildRequest req : player.buildQueue()){
            if(req.breaking) continue;
            if(req.bounds(Tmp.r2).overlaps(Tmp.r1)){
                drawBreaking(req);
            }
        }

        for(BuildRequest req : selectRequests){
            if(req.breaking) continue;
            if(req.bounds(Tmp.r2).overlaps(Tmp.r1)){
                drawBreaking(req);
            }
        }

        for(BrokenBlock req : player.getTeam().data().brokenBlocks){
            Block block = content.block(req.block);
            if(block.bounds(req.x, req.y, Tmp.r2).overlaps(Tmp.r1)){
                drawSelected(req.x, req.y, content.block(req.block), Pal.remove);
            }
        }

        Draw.color(Color.valueOf("e5545466"));
        float x = result.x;
        float y = result.y;
        float width = result.x2 - result.x;
        float height = result.y2 - result.y;

        float stroke;

        x -= 0;
        y -= 0;
        width += 0 * 2.0F;
        height += 0 * 2.0F;
        Core.gl.glHint(Core.gl.GL_SAMPLE_BUFFERS, 1);
        Core.gl.glHint(Core.gl.GL_SAMPLES, 4);
        Lines.precise(true);

        stroke = height / 2;
        Fill.crect(x, y, width, stroke);
        Fill.crect(x, y + height, width, -stroke);

        stroke = width / 2;
        Fill.crect(x + width, y, -stroke, height);
        Fill.crect(x, y, stroke, height);


//        Lines.stroke(2f);

//        Draw.color(Pal.removeBack);
//        Lines.rect(result.x, result.y - 1, result.x2 - result.x, result.y2 - result.y);
//        Draw.color(Pal.remove);
//        Lines.rect(result.x, result.y, result.x2 - result.x, result.y2 - result.y);
    }

    protected void drawSelection(int x1, int y1, int x2, int y2, int maxLength){
        NormalizeDrawResult result = Placement.normalizeDrawArea(Blocks.air, x1, y1, x2, y2, false, maxLength, 1f);

        Lines.stroke(2f);

        Draw.color(Pal.accentBack);
//        Lines.rect(result.x, result.y - 1, result.x2 - result.x, result.y2 - result.y);
//        Core.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT | (Core.graphics.getBufferFormat().coverageSampling?GL20.GL_COVERAGE_BUFFER_BIT_NV:0));
//        Core.gl20.glSampleCoverage(0.1F, false);
//        System.out.println(Core.gl20.GL_SAMPLES);
        Core.gl.glEnable(2848);

//        Core.gl20.glEnable(GL_LINE_SMOOTH);
//        Core.gl.glEnable(GL_BLEND);

//        Core.gl.glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
//        Core.gl.glHint(3154, GL_NICEST);
//        Core.gl20.glEnable(5377);
        Draw.color(Color.valueOf("ffd37f66"));
//        Draw.color(Pal.accent);
        float x = result.x;
        float y = result.y;
        float width = result.x2 - result.x;
        float height = result.y2 - result.y;
//        float stroke = 1 / renderer.getScale();
//        stroke *= 4;
        float stroke = 1F;
//        if(renderer.getScale() < 1) {
//            stroke = 3F;
//        }
//        if(renderer.getScale() < 0.5) {
//            stroke = 4F;
//        }
//        if(renderer.getScale() < 0.25) {
//            stroke = 5F;
//        }
        x -= 0;
        y -= 0;
        width += 0 * 2.0F;
        height += 0 * 2.0F;
        Core.gl.glHint(Core.gl.GL_SAMPLE_BUFFERS, 1);
        Core.gl.glHint(Core.gl.GL_SAMPLES, 4);
        Lines.precise(true);
//        stroke = 30F;
//        Lines.rect(x, y, width, height, );
//
//        Core.gl.
        stroke = height / 2;
        Fill.crect(x, y, width, stroke);
        Fill.crect(x, y + height, width, -stroke);

        stroke = width / 2;
        Fill.crect(x + width, y, -stroke, height);
        Fill.crect(x, y, stroke, height);
//        Lines.rect();
//        Lines.stroke(stroke);
//        Lines.rect(result.x, result.y, result.x2 - result.x, result.y2 - result.y);
//        ShapeRenderer shape = new
    }

    protected void flushSelectRequests(Array<BuildRequest> requests){
        for(BuildRequest req : requests){
            if(req.block != null && validPlace(req.x, req.y, req.block, req.rotation)){
                BuildRequest other = getRequest(req.x, req.y, req.block.size, null);
                if(other == null){
                    selectRequests.add(req.copy());
                }else if(!other.breaking && other.x == req.x && other.y == req.y && other.block.size == req.block.size){
                    selectRequests.remove(other);
                    selectRequests.add(req.copy());
                }
            }
        }
    }

    protected void flushRequests(Array<BuildRequest> requests){
        for(BuildRequest req : requests){
            if(req.block != null && validPlace(req.x, req.y, req.block, req.rotation)){
                BuildRequest copy = req.copy();
                if(copy.hasConfig && copy.block.posConfig){
                    copy.config = Pos.get(Pos.x(copy.config) + copy.x - copy.originalX, Pos.y(copy.config) + copy.y - copy.originalY);
                }
                player.addBuildRequest(copy, !Core.input.shift());
            }
        }
    }

    protected void drawRequest(BuildRequest request){
        request.block.drawRequest(request, allRequests(), validPlace(request.x, request.y, request.block, request.rotation));
    }

    /** Draws a placement icon for a specific block. */
    protected void drawRequest(int x, int y, Block block, int rotation){
        brequest.set(x, y, rotation, block);
        brequest.animScale = 1f;
        block.drawRequest(brequest, allRequests(), validPlace(x, y, block, rotation));
    }

    /** Remove everything from the queue in a selection. */
    protected void removeSelection(int x1, int y1, int x2, int y2){
        removeSelection(x1, y1, x2, y2, false);
    }

    /** Remove everything from the queue in a selection. */
    protected void removeSelection(int x1, int y1, int x2, int y2, boolean flush){
        NormalizeResult result = Placement.normalizeArea(x1, y1, x2, y2, rotation, false, maxLength);
        for(int x = 0; x <= Math.abs(result.x2 - result.x); x++){
            for(int y = 0; y <= Math.abs(result.y2 - result.y); y++){
                int wx = x1 + x * Mathf.sign(x2 - x1);
                int wy = y1 + y * Mathf.sign(y2 - y1);

                Tile tile = world.ltile(wx, wy);

                if(tile == null) continue;

                if(!flush){
                    tryBreakBlock(wx, wy);
                }else if(validBreak(tile.x, tile.y) && !selectRequests.contains(r -> r.tile() != null && r.tile().link() == tile)){
                    selectRequests.add(new BuildRequest(tile.x, tile.y));
                }
            }
        }

        //remove build requests
        Tmp.r1.set(result.x * tilesize, result.y * tilesize, (result.x2 - result.x) * tilesize, (result.y2 - result.y) * tilesize);

        Iterator<BuildRequest> it = player.buildQueue().iterator();
        while(it.hasNext()){
            BuildRequest req = it.next();
            if(!req.breaking && req.bounds(Tmp.r2).overlaps(Tmp.r1)){
                it.remove();
            }
        }

        it = selectRequests.iterator();
        while(it.hasNext()){
            BuildRequest req = it.next();
            if(!req.breaking && req.bounds(Tmp.r2).overlaps(Tmp.r1)){
                it.remove();
            }
        }

        //remove blocks to rebuild
        Iterator<BrokenBlock> broken = state.teams.get(player.getTeam()).brokenBlocks.iterator();
        while(broken.hasNext()){
            BrokenBlock req = broken.next();
            Block block = content.block(req.block);
            if(block.bounds(req.x, req.y, Tmp.r2).overlaps(Tmp.r1)){
                broken.remove();
            }
        }
    }

    protected void updateLine(int x1, int y1, int x2, int y2){
        lineRequests.clear();
        iterateLine(x1, y1, x2, y2, l -> {
            rotation = l.rotation;
            BuildRequest req;
            if(block instanceof Chain){
                req = new BuildRequest(l.x, l.y, l.rotation, ((Chain)block).blocks[(l.x + l.y) % ((Chain)block).blocks.length]);
            }else{
                req = new BuildRequest(l.x, l.y, l.rotation, block);
            }
            req.animScale = 1f;
            lineRequests.add(req);
        });

        if(Core.settings.getBool("blockreplace")){
            lineRequests.each(req -> {
                Block replace = req.block.getReplacement(req, lineRequests);
                if(replace.unlockedCur()){
                    req.block = replace;
                }
            });
        }
    }

    protected void updateLine(int x1, int y1){
        updateLine(x1, y1, tileX(getMouseX()), tileY(getMouseY()));
    }

    /** Handles tile tap events that are not platform specific. */
    boolean tileTapped(Tile tile){
        tile = tile.link();

        boolean consumed = false, showedInventory = false;

        //check if tapped block is configurable
        if(tile.block().configurable){
            consumed = true;
            if(((!frag.config.isShown() && tile.block().shouldShowConfigure(tile, player)) //if the config fragment is hidden, show
            //alternatively, the current selected block can 'agree' to switch config tiles
            || (frag.config.isShown() && frag.config.getSelectedTile().block().onConfigureTileTapped(frag.config.getSelectedTile(), tile)))){
                Sounds.click.at(tile);
                frag.config.showConfig(tile);
            }
            //otherwise...
        }else if(!frag.config.hasConfigMouse()){ //make sure a configuration fragment isn't on the cursor
            //then, if it's shown and the current block 'agrees' to hide, hide it.
            if(frag.config.isShown() && frag.config.getSelectedTile().block().onConfigureTileTapped(frag.config.getSelectedTile(), tile)){
                consumed = true;
                frag.config.hideConfig();
            }

            if(frag.config.isShown()){
                consumed = true;
            }
        }

        //call tapped event
        if(!consumed && tile.interactable(player.getTeam())){
            Call.onTileTapped(player, tile);
        }

        //consume tap event if necessary
        if(tile.interactable(player.getTeam()) && tile.block().consumesTap){
            consumed = true;
        }else if(tile.interactable(player.getTeam()) && tile.block().synthetic() && !consumed){
            if(tile.block().hasItems && tile.entity.items.total() > 0){
                frag.inv.showFor(tile);
                consumed = true;
                showedInventory = true;
            }
        }

        if(!showedInventory){
            frag.inv.hide();
        }

        return consumed;
    }

    /** Tries to select the player to drop off items, returns true if successful. */
    boolean tryTapPlayer(float x, float y){
        if(canTapPlayer(x, y)){
            droppingItem = true;
            return true;
        }
        return false;
    }

    boolean canTapPlayer(float x, float y){
        return Mathf.dst(x, y, player.x, player.y) <= playerSelectRange && player.item().amount > 0;
    }

    /** Tries to begin mining a tile, returns true if successful. */
    boolean tryBeginMine(Tile tile){
        if(canMine(tile)){
            //if a block is clicked twice, reset it
            player.setMineTile(player.getMineTile() == tile ? null : tile);
            return true;
        }
        return false;
    }

    boolean canMine(Tile tile){
        return !Core.scene.hasMouse()
        && tile.drop() != null && tile.drop().hardness <= player.mech.drillPower
        && !(tile.floor().playerUnmineable && tile.overlay().itemDrop == null)
        && player.acceptsItem(tile.drop())
        && tile.block() == Blocks.air && player.dst(tile.worldx(), tile.worldy()) <= Player.mineDistance;
    }

    /** Returns the tile at the specified MOUSE coordinates. */
    Tile tileAt(float x, float y){
        return world.tile(tileX(x), tileY(y));
    }

    int rawTileX(){
        return world.toTile(Core.input.mouseWorld().x);
    }

    int rawTileY(){
        return world.toTile(Core.input.mouseWorld().y);
    }

    int tileX(float cursorX){
        Vec2 vec = Core.input.mouseWorld(cursorX, 0);
        if(selectedBlock()){
            vec.sub(block.offset(), block.offset());
        }
        return world.toTile(vec.x);
    }

    int tileY(float cursorY){
        Vec2 vec = Core.input.mouseWorld(0, cursorY);
        if(selectedBlock()){
            vec.sub(block.offset(), block.offset());
        }
        return world.toTile(vec.y);
    }

    public boolean selectedBlock(){
        return isPlacing();
    }

    public boolean isPlacing(){
        return block != null;
    }

    public boolean isBreaking(){
        return false;
    }

    public float mouseAngle(float x, float y){
        return Core.input.mouseWorld(getMouseX(), getMouseY()).sub(x, y).angle();
    }

    public void remove(){
        Core.input.removeProcessor(this);
        frag.remove();
        if(Core.scene != null){
            Table table = (Table)Core.scene.find("inputTable");
            if(table != null){
                table.clear();
            }
        }
        if(detector != null){
            Core.input.removeProcessor(detector);
        }
        if(uiGroup != null){
            uiGroup.remove();
            uiGroup = null;
        }
    }

    public void add(){
        Core.input.getInputProcessors().remove(i -> i instanceof InputHandler || (i instanceof GestureDetector && ((GestureDetector)i).getListener() instanceof InputHandler));
        Core.input.addProcessor(detector = new GestureDetector(20, 0.5f, 0.3f, 0.15f, this));
        Core.input.addProcessor(this);
        if(Core.scene != null){
            Table table = (Table)Core.scene.find("inputTable");
            if(table != null){
                table.clear();
                buildPlacementUI(table);
            }

            uiGroup = new WidgetGroup();
            uiGroup.touchable(Touchable.childrenOnly);
            uiGroup.setFillParent(true);
            ui.hudGroup.addChild(uiGroup);
            buildUI(uiGroup);

            frag.add();
        }

        if(player != null){
            player.isBuilding = true;
        }
    }

    public boolean canShoot(){
        return block == null && !Core.scene.hasMouse() && !onConfigurable() && !isDroppingItem();
    }

    public boolean onConfigurable(){
        return false;
    }

    public boolean isDroppingItem(){
        return droppingItem;
    }

    public void tryDropItems(Tile tile, float x, float y){
        if(!droppingItem || player.item().amount <= 0 || canTapPlayer(x, y) || state.isPaused() ){
            droppingItem = false;
            return;
        }

        droppingItem = false;

        ItemStack stack = player.item();

        if(tile.block().acceptStack(stack.item, stack.amount, tile, player) > 0 && tile.interactable(player.getTeam()) && tile.block().hasItems && player.item().amount > 0 && !player.isTransferring && tile.interactable(player.getTeam())){
            Call.transferInventory(player, tile);
            if(Client.recordingWaypoints){
                Waypoint w = new Waypoint(camera.position.x, camera.position.y);
                w.dump = tile.pos();
                Client.waypoints.add(w);
            }
        }else{
            Call.dropItem(player.angleTo(x, y));
        }
    }

    public void tryPlaceBlock(int x, int y){
        if(block != null && validPlace(x, y, block, rotation)){
            placeBlock(x, y, block, rotation);
        }
    }

    public void tryBreakBlock(int x, int y){
        if(validBreak(x, y)){
            breakBlock(x, y);
        }
    }

    public boolean validPlace(int x, int y, Block type, int rotation){
        return validPlace(x, y, type, rotation, null);
    }

    public boolean validPlace(int x, int y, Block type, int rotation, BuildRequest ignore){
        for(BuildRequest req : player.buildQueue()){
            if(req != ignore
                    && !req.breaking
                    && req.block.bounds(req.x, req.y, Tmp.r1).overlaps(type.bounds(x, y, Tmp.r2))
                    && !(type.canReplace(req.block) && Tmp.r1.equals(Tmp.r2))){
                return false;
            }
        }
        return Build.validPlace(player.getTeam(), x, y, type, rotation);
    }

    public boolean validBreak(int x, int y){
        return Build.validBreak(player.getTeam(), x, y);
    }

    public void placeBlock(int x, int y, Block block, int rotation){
        BuildRequest req = getRequest(x, y);
        if(req != null){
            player.buildQueue().remove(req);
        }
        BuildRequest req2 = new BuildRequest(x, y, rotation, block);
        player.addBuildRequest(req2);
    }

    public void breakBlock(int x, int y){
        Tile tile = world.ltile(x, y);
        BuildRequest req = new BuildRequest(tile.x, tile.y);
        player.addBuildRequest(req, !Core.input.shift());
    }

    public void drawArrow(Block block, int x, int y, int rotation){
        drawArrow(block, x, y, rotation, validPlace(x, y, block, rotation));
    }

    public void drawArrow(Block block, int x, int y, int rotation, boolean valid){
        Draw.color(!valid ? Pal.removeBack : Pal.accentBack);
        Draw.rect(Core.atlas.find("place-arrow"),
        x * tilesize + block.offset(),
        y * tilesize + block.offset() - 1,
        Core.atlas.find("place-arrow").getWidth() * Draw.scl,
        Core.atlas.find("place-arrow").getHeight() * Draw.scl, rotation * 90 - 90);

        Draw.color(!valid ? Pal.remove : Pal.accent);
        Draw.rect(Core.atlas.find("place-arrow"),
        x * tilesize + block.offset(),
        y * tilesize + block.offset(),
        Core.atlas.find("place-arrow").getWidth() * Draw.scl,
        Core.atlas.find("place-arrow").getHeight() * Draw.scl, rotation * 90 - 90);
    }

    void iterateLine(int startX, int startY, int endX, int endY, Cons<PlaceLine> cons){
        Array<Point2> points;
        boolean diagonal = Core.input.keyDown(Binding.diagonal_placement);

        if(Core.settings.getBool("swapdiagonal") && mobile){
            diagonal = !diagonal;
        }

        if(block instanceof PowerNode){
            diagonal = !diagonal;
        }

        if(diagonal){
            points = Placement.pathfindLine(block != null && block.conveyorPlacement, startX, startY, endX, endY);
        }else{
            points = Placement.normalizeLine(startX, startY, endX, endY);
        }

        if(block instanceof PowerNode){
            Array<Point2> skip = new Array<>();
            
            for(int i = 1; i < points.size; i++){
                int overlaps = 0;
                Point2 point = points.get(i);

                //check with how many powernodes the *next* tile will overlap
                for(int j = 0; j < i; j++){
                    if(!skip.contains(points.get(j)) && ((PowerNode)block).overlaps(world.ltile(point.x, point.y), world.ltile(points.get(j).x, points.get(j).y))){
                        overlaps++;
                    }
                }

                //if it's more than one, it can bridge the gap
                if(overlaps > 1){
                    skip.add(points.get(i-1));
                }
            }
            //remove skipped points
            points.removeAll(skip);
        }

        float angle = Angles.angle(startX, startY, endX, endY);
        int baseRotation = rotation;
        if(!overrideLineRotation || diagonal){
            baseRotation = (startX == endX && startY == endY) ? rotation : ((int)((angle + 45) / 90f)) % 4;
        }

        Tmp.r3.set(-1, -1, 0, 0);

        for(int i = 0; i < points.size; i++){
            Point2 point = points.get(i);

            if(block != null && Tmp.r2.setSize(block.size * tilesize).setCenter(point.x * tilesize + block.offset(), point.y * tilesize + block.offset()).overlaps(Tmp.r3)){
                continue;
            }

            Point2 next = i == points.size - 1 ? null : points.get(i + 1);
            line.x = point.x;
            line.y = point.y;
            if(!overrideLineRotation || diagonal){
                line.rotation = next != null ? Tile.relativeTo(point.x, point.y, next.x, next.y) : baseRotation;
            }else{
                line.rotation = rotation;
            }
            line.last = next == null;
            cons.get(line);

            Tmp.r3.setSize(block.size * tilesize).setCenter(point.x * tilesize + block.offset(), point.y * tilesize + block.offset());
        }
    }

    class PlaceLine{
        public int x, y, rotation;
        public boolean last;
    }
}
