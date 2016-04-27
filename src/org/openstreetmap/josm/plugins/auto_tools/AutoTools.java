package org.openstreetmap.josm.plugins.auto_tools;

import java.awt.event.KeyEvent;
import javax.swing.*;
import org.openstreetmap.josm.Main;
import org.openstreetmap.josm.gui.help.HelpUtil;
import static org.openstreetmap.josm.gui.mappaint.mapcss.ExpressionFactory.Functions.tr;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;
import org.openstreetmap.josm.plugins.auto_tools.actions.MergeBuildingsAction;

public class AutoTools extends Plugin {

    public AutoTools(PluginInformation info) {
        super(info);
        final JMenu loadTaskMenu = Main.main.menu.addMenu(
                "Auto Tools", tr("Auto Tools"), KeyEvent.VK_K,
                Main.main.menu.getDefaultMenuPos(), HelpUtil.ht("/Plugin/task")
        );
        loadTaskMenu.add(new JMenuItem(new MergeBuildingsAction("Merge two Buildings")));
        loadTaskMenu.add(new JSeparator());
        
    }
}