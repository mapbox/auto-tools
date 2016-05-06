package org.openstreetmap.josm.plugins.auto_tools.actions;

import java.awt.Point;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.Action;
import javax.swing.JOptionPane;
import org.openstreetmap.josm.Main;
import org.openstreetmap.josm.actions.SplitWayAction;
import static org.openstreetmap.josm.actions.SplitWayAction.buildSplitChunks;
import static org.openstreetmap.josm.actions.SplitWayAction.splitWay;
import org.openstreetmap.josm.actions.mapmode.MapMode;
import org.openstreetmap.josm.actions.mapmode.SelectAction;
import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.ChangeCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.osm.WaySegment;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.gui.NavigatableComponent;
import org.openstreetmap.josm.gui.Notification;
import static org.openstreetmap.josm.tools.I18n.tr;
import static org.openstreetmap.josm.tools.I18n.trn;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Pair;
import org.openstreetmap.josm.tools.Shortcut;
import org.openstreetmap.josm.tools.Utils;

/**
 *
 * @author samely
 */
public class SplittingTool extends MapMode {

    private Point mousePos;
    private double toleranceMultiplier;
    private static int snapToIntersectionThreshold;

    public SplittingTool(MapFrame mapFrame) {
        super(tr("SplittingTool"), "node/autonode", tr("Draw nodes"),
                Shortcut.registerShortcut("mapmode:SplittingTool", tr("Mode:SplittingTool", tr("Draw")), KeyEvent.VK_T, Shortcut.DIRECT),
                mapFrame, ImageProvider.getCursor("crosshair", null));
    }

    @Override
    public void enterMode() {
        if (!isEnabled()) {
            return;
        }
        super.enterMode();
        toleranceMultiplier = 0.01 * NavigatableComponent.PROP_SNAP_DISTANCE.get();
        Main.map.mapView.addMouseListener(this);
    }

    @Override
    public void exitMode() {
        super.exitMode();
        Main.map.mapView.removeMouseListener(this);
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        if (e.getButton() == MouseEvent.BUTTON3 || e.getButton() != MouseEvent.BUTTON1 || !Main.map.mapView.isActiveLayerDrawable()) {
            return;
        }

        // Focus to enable shortcuts       
        Main.map.mapView.requestFocus();

        //Control key modifiers
        updateKeyModifiers(e);
        mousePos = e.getPoint();

        DataSet ds = getCurrentDataSet();
        Collection<OsmPrimitive> selection = new ArrayList<>(ds.getSelected());

        boolean newNode = false;
        Node n = null;

        n = Main.map.mapView.getNearestNode(mousePos, OsmPrimitive.isSelectablePredicate);

        if (n != null) {
            // user clicked on node
            selection.add(n);
            if (!selection.isEmpty()) {
                SplitRoad(n);
                return;
            }
        } else {
            if (n != null) {

                EastNorth foundPoint = n.getEastNorth();
                // project found node to snapping line
                // do not add new node if there is some node within snapping distance
                double tolerance = Main.map.mapView.getDist100Pixel() * toleranceMultiplier;
                if (foundPoint.distance(foundPoint) > tolerance) {
                    n = new Node(foundPoint); // point != projected, so we create new node
                    newNode = true;
                }

            } else { // n==null, no node found in clicked area
                EastNorth mouseEN = Main.map.mapView.getEastNorth(e.getX(), e.getY());
                n = new Node(mouseEN); //create node at clicked point                
                newNode = true;
            }
        }

        Collection<Command> cmds = new LinkedList<>();
        Collection<OsmPrimitive> newSelection = new LinkedList<>(ds.getSelected());
        List<Way> reuseWays = new ArrayList<>();
        List<Way> replacedWays = new ArrayList<>();

        if (newNode) {
            if (n.getCoor().isOutSideWorld()) {
                JOptionPane.showMessageDialog(
                        Main.parent,
                        tr("Cannot add a node outside of the world."),
                        tr("Warning"),
                        JOptionPane.WARNING_MESSAGE
                );
                return;
            }

            cmds.add(new AddCommand(n));

            //This is the part that insert the node into the way
            if (!ctrl) {
                // Insert the node into all the nearby way segments
                List<WaySegment> wss = Main.map.mapView.getNearestWaySegments(Main.map.mapView.getPoint(n), OsmPrimitive.isSelectablePredicate);
                insertNodeIntoAllNearbySegments(wss, n, newSelection, cmds, replacedWays, reuseWays);
            }
        }

        Command c = new SequenceCommand("Add node into way and connect", cmds);
        Main.main.undoRedo.add(c);

        if (OsmPrimitive.getFilteredList(n.getReferrers(), Way.class).isEmpty()) {
            getCurrentDataSet().removePrimitive(n.getPrimitiveId());
        } else {
            SplitRoad(n);
        }

        newSelection.clear();
        getCurrentDataSet().setSelected(newSelection);
        //return to select mode
        Main.map.selectMapMode(Main.map.mapModeSelect);

    }

    public static Way getWayForNodeToSplit(Node n) {
        Way way = null;
        for (Way w : Utils.filteredCollection(n.getReferrers(), Way.class)) {
            if (!w.isUsable() || w.getNodesCount() < 1) {
                continue;
            }
            way = w;
        }
        return way;
    }

    private void insertNodeIntoAllNearbySegments(List<WaySegment> wss, Node n, Collection<OsmPrimitive> newSelection,
            Collection<Command> cmds, List<Way> replacedWays, List<Way> reuseWays) {
        Map<Way, List<Integer>> insertPoints = new HashMap<>();
        for (WaySegment ws : wss) {
            List<Integer> is;
            if (insertPoints.containsKey(ws.way)) {
                is = insertPoints.get(ws.way);
            } else {
                is = new ArrayList<>();
                insertPoints.put(ws.way, is);
            }

            is.add(ws.lowerIndex);
        }

        Set<Pair<Node, Node>> segSet = new HashSet<>();

        for (Map.Entry<Way, List<Integer>> insertPoint : insertPoints.entrySet()) {
            Way w = insertPoint.getKey();
            List<Integer> is = insertPoint.getValue();

            Way wnew = new Way(w);

            pruneSuccsAndReverse(is);
            for (int i : is) {
                segSet.add(Pair.sort(new Pair<>(w.getNode(i), w.getNode(i + 1))));
                wnew.addNode(i + 1, n);
            }

            cmds.add(new ChangeCommand(insertPoint.getKey(), wnew));
            replacedWays.add(insertPoint.getKey());
            reuseWays.add(wnew);
        }

        adjustNode(segSet, n);
    }

    private static void adjustNode(Collection<Pair<Node, Node>> segs, Node n) {

        switch (segs.size()) {
            case 0:
                return;
            case 2:
                // This computes the intersection between the two segments and adjusts the node position.
                Iterator<Pair<Node, Node>> i = segs.iterator();
                Pair<Node, Node> seg = i.next();
                EastNorth pA = seg.a.getEastNorth();
                EastNorth pB = seg.b.getEastNorth();
                seg = i.next();
                EastNorth pC = seg.a.getEastNorth();
                EastNorth pD = seg.b.getEastNorth();

                double u = det(pB.east() - pA.east(), pB.north() - pA.north(), pC.east() - pD.east(), pC.north() - pD.north());

                // Check for parallel segments and do nothing if they are
                // In practice this will probably only happen when a way has been duplicated
                if (u == 0) {
                    return;
                }

                // q is a number between 0 and 1
                // It is the point in the segment where the intersection occurs
                // if the segment is scaled to lenght 1
                double q = det(pB.north() - pC.north(), pB.east() - pC.east(), pD.north() - pC.north(), pD.east() - pC.east()) / u;
                EastNorth intersection = new EastNorth(
                        pB.east() + q * (pA.east() - pB.east()),
                        pB.north() + q * (pA.north() - pB.north()));

                // only adjust to intersection if within snapToIntersectionThreshold pixel of mouse click; otherwise
                // fall through to default action.
                // (for semi-parallel lines, intersection might be miles away!)
                if (Main.map.mapView.getPoint2D(n).distance(Main.map.mapView.getPoint2D(intersection)) < snapToIntersectionThreshold) {
                    n.setEastNorth(intersection);
                    return;
                }
            default:
                EastNorth p = n.getEastNorth();
                seg = segs.iterator().next();
                pA = seg.a.getEastNorth();
                pB = seg.b.getEastNorth();
                double a = p.distanceSq(pB);
                double b = p.distanceSq(pA);
                double c = pA.distanceSq(pB);
                q = (a - b + c) / (2 * c);
                n.setEastNorth(new EastNorth(pB.east() + q * (pA.east() - pB.east()), pB.north() + q * (pA.north() - pB.north())));
        }
    }

    static double det(double a, double b, double c, double d) {
        return a * d - b * c;
    }

    private static void pruneSuccsAndReverse(List<Integer> is) {
        Set<Integer> is2 = new HashSet<>();
        for (int i : is) {
            if (!is2.contains(i - 1) && !is2.contains(i + 1)) {
                is2.add(i);
            }
        }
        is.clear();
        is.addAll(is2);
        Collections.sort(is);
        Collections.reverse(is);
    }

    public void SplitRoad(Node node) {

        DataSet ds = getCurrentDataSet();
        Collection<OsmPrimitive> selection = new ArrayList<>(ds.getSelected());
        selection.add(node);
        selection.add(getWayForNodeToSplit(node));

        List<Node> selectedNodes = OsmPrimitive.getFilteredList(selection, Node.class);
        List<Way> selectedWays = OsmPrimitive.getFilteredList(selection, Way.class);
        List<Relation> selectedRelations = OsmPrimitive.getFilteredList(selection, Relation.class);
        List<Way> applicableWays = getApplicableWays(selectedWays, selectedNodes);

        if (applicableWays == null) {
            new Notification(
                    tr("The current selection cannot be used for splitting - no node is selected."))
                    .setIcon(JOptionPane.WARNING_MESSAGE)
                    .show();
            return;
        } else if (applicableWays.isEmpty()) {
            new Notification(
                    tr("The selected nodes do not share the same way."))
                    .setIcon(JOptionPane.WARNING_MESSAGE)
                    .show();
            return;
        }

        // If several ways have been found, remove ways that doesn't have selected node in the middle
        if (applicableWays.size() > 1) {
            WAY_LOOP:
            for (Iterator<Way> it = applicableWays.iterator(); it.hasNext();) {
                Way w = it.next();
                for (Node no : selectedNodes) {
                    if (!w.isInnerNode(no)) {
                        it.remove();
                        continue WAY_LOOP;
                    }
                }
            }
        }

        if (applicableWays.isEmpty()) {
            new Notification(
                    trn("The selected node is not in the middle of any way.",
                            "The selected nodes are not in the middle of any way.",
                            selectedNodes.size()))
                    .setIcon(JOptionPane.WARNING_MESSAGE)
                    .show();
            return;
        } else if (applicableWays.size() > 1) {
            new Notification(
                    trn("There is more than one way using the node you selected. Please select the way also.",
                            "There is more than one way using the nodes you selected. Please select the way also.",
                            selectedNodes.size()))
                    .setIcon(JOptionPane.WARNING_MESSAGE)
                    .show();
            return;
        }

        // Finally, applicableWays contains only one perfect way
        Way selectedWay = applicableWays.get(0);

        List<List<Node>> wayChunks = buildSplitChunks(selectedWay, selectedNodes);
        if (wayChunks != null) {
            List<OsmPrimitive> sel = new ArrayList<OsmPrimitive>(selectedWays.size() + selectedRelations.size());
            sel.addAll(selectedWays);
            sel.addAll(selectedRelations);
            SplitWayAction.SplitWayResult result = splitWay(getEditLayer(), selectedWay, wayChunks, sel);
            Main.main.undoRedo.add(result.getCommand());
            //getCurrentDataSet().setSelected(result.getNewSelection());
        }

    }

    private List<Way> getApplicableWays(List<Way> selectedWays, List<Node> selectedNodes) {
        if (selectedNodes.isEmpty()) {
            return null;
        }

        // Special case - one of the selected ways touches (not cross) way that we want to split
        if (selectedNodes.size() == 1) {
            Node n = selectedNodes.get(0);
            List<Way> referedWays = OsmPrimitive.getFilteredList(n.getReferrers(), Way.class);
            Way inTheMiddle = null;
            boolean foundSelected = false;
            for (Way w : referedWays) {
                if (selectedWays.contains(w)) {
                    foundSelected = true;
                }
                if (w.getNode(0) != n && w.getNode(w.getNodesCount() - 1) != n) {
                    if (inTheMiddle == null) {
                        inTheMiddle = w;
                    } else {
                        inTheMiddle = null;
                        break;
                    }
                }
            }
            if (foundSelected && inTheMiddle != null) {
                return Collections.singletonList(inTheMiddle);
            }
        }

        // List of ways shared by all nodes
        List<Way> result = new ArrayList<Way>(OsmPrimitive.getFilteredList(selectedNodes.get(0).getReferrers(), Way.class));
        for (int i = 1; i < selectedNodes.size(); i++) {
            List<OsmPrimitive> ref = selectedNodes.get(i).getReferrers();
            for (Iterator<Way> it = result.iterator(); it.hasNext();) {
                if (!ref.contains(it.next())) {
                    it.remove();
                }
            }
        }

        // Remove broken ways
        for (Iterator<Way> it = result.iterator(); it.hasNext();) {
            if (it.next().getNodesCount() <= 2) {
                it.remove();
            }
        }

        if (selectedWays.isEmpty()) {
            return result;
        } else {
            // Return only selected ways
            for (Iterator<Way> it = result.iterator(); it.hasNext();) {
                if (!selectedWays.contains(it.next())) {
                    it.remove();
                }
            }
            return result;
        }
    }

}