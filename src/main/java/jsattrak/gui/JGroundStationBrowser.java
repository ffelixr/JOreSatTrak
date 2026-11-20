/*
 * JGroundStationBrowser.java
 * =====================================================================
 *   This file is part of JSatTrak.
 *
 *   Copyright 2007-2013 Shawn E. Gano
 *   
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *   
 *       http://www.apache.org/licenses/LICENSE-2.0
 *   
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 * =====================================================================
 *
 * Created on December 12, 2007, 2:14 PM
 */

package jsattrak.gui;

import java.awt.Frame;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.swing.JOptionPane;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import jsattrak.utilities.GroundStationCatalogRepository;
import jsattrak.utilities.TreeGroundStationTransferHandler;

/**
 *
 * @author  sgano
 */
public class JGroundStationBrowser extends javax.swing.JPanel implements java.io.Serializable
{
    public static String groundStationDB = GroundStationCatalogRepository.DEFAULT_BASE_DB;
    public static String groundStationDir = "data/groundstations/";
    public static String groundStationCustomDB = GroundStationCatalogRepository.DEFAULT_CUSTOM_DB;
    
    DefaultTreeModel treeModel;
    DefaultMutableTreeNode topTreeNode; // top node
    DefaultMutableTreeNode customSecondaryNode;
    
    Hashtable<String,double []> gsHash = new Hashtable<String, double[]>(); // stores Ground Station name and LLA info [deg,deg,meters]
    
    Frame parent;
    private final transient GroundStationCatalogRepository repository;
    
    /** Creates new form JGroundStationBrowser */
    public JGroundStationBrowser(Frame parent)
    {
        this(parent, GroundStationCatalogRepository.createDefault());
    }

    /** Creates new form JGroundStationBrowser backed by the supplied repository. */
    public JGroundStationBrowser(Frame parent, GroundStationCatalogRepository repository)
    {
        this.parent = parent;
        this.repository = Objects.requireNonNull(repository, "repository");
        
        initComponents();
        
        // top tree node
        topTreeNode = new DefaultMutableTreeNode("Ground Stations");
        treeModel = new DefaultTreeModel(topTreeNode); // create tree model using root node
        groundStationTree.setModel(treeModel); // set the tree's model
        
        reloadCatalog();
        
        // Drag and Drop Handler
        // setup transfer handler
        //must also enable drag in IDE gui for tree
        groundStationTree.setTransferHandler(new TreeGroundStationTransferHandler(gsHash));
        
        // allow mutiple selections
        groundStationTree.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        
        
    } // constructor

    /**
     * Reloads ground station catalogs from the repository and refreshes the tree and lookup map.
     */
    public void reloadCatalog()
    {
        topTreeNode.removeAllChildren();
        gsHash.clear();
        customSecondaryNode = null;

        try
        {
            Map<String, List<GroundStationCatalogRepository.CatalogEntry>> grouped = repository.loadGroupedCatalog();
            int gsCount = 0;

            for (Map.Entry<String, List<GroundStationCatalogRepository.CatalogEntry>> entry : grouped.entrySet())
            {
                DefaultMutableTreeNode networkNode = new DefaultMutableTreeNode(entry.getKey());
                topTreeNode.add(networkNode);

                if (GroundStationCatalogRepository.CUSTOM_NETWORK.equals(entry.getKey()))
                {
                    customSecondaryNode = networkNode;
                }

                for (GroundStationCatalogRepository.CatalogEntry station : entry.getValue())
                {
                    gsHash.put(station.name(), new double[]{station.latDeg(), station.lonDeg(), station.altMeters()});
                    networkNode.add(new DefaultMutableTreeNode(station.name()));
                    gsCount++;
                }
            }

            if (customSecondaryNode == null)
            {
                customSecondaryNode = new DefaultMutableTreeNode(GroundStationCatalogRepository.CUSTOM_NETWORK);
                topTreeNode.add(customSecondaryNode);
            }

            treeModel.reload(topTreeNode);

            // add text to bottom
            statusTextField.setText("Total Ground Stations loaded: " + gsCount);

            // auto expand root node
            groundStationTree.expandRow(0);
        }
        catch (Exception e)
        {
            System.out.println("ERROR IN GROUND STATION READING POSSIBLE FILE FORMAT OR MISSING FILES:");
            e.printStackTrace();
            JOptionPane.showMessageDialog(parent, "Error Loading Ground Station Data. Check data.\n" + e.toString(), "Data LOADING ERROR", JOptionPane.ERROR_MESSAGE);
        }
    }
    
    /** This method is called from within the constructor to
     * initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is
     * always regenerated by the Form Editor.
     */
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents()
    {

        jLabel1 = new javax.swing.JLabel();
        jScrollPane1 = new javax.swing.JScrollPane();
        groundStationTree = new javax.swing.JTree();
        statusTextField = new javax.swing.JTextField();
        addGSButton = new javax.swing.JButton();

        jLabel1.setFont(new java.awt.Font("Tahoma", 1, 14));
        jLabel1.setText("Ground Station Browser");

        groundStationTree.setDragEnabled(true);
        groundStationTree.addTreeSelectionListener(new javax.swing.event.TreeSelectionListener()
        {
            public void valueChanged(javax.swing.event.TreeSelectionEvent evt)
            {
                groundStationTreeValueChanged(evt);
            }
        });
        groundStationTree.addPropertyChangeListener(new java.beans.PropertyChangeListener()
        {
            public void propertyChange(java.beans.PropertyChangeEvent evt)
            {
                groundStationTreePropertyChange(evt);
            }
        });
        jScrollPane1.setViewportView(groundStationTree);

        statusTextField.addActionListener(new java.awt.event.ActionListener()
        {
            public void actionPerformed(java.awt.event.ActionEvent evt)
            {
                statusTextFieldActionPerformed(evt);
            }
        });

        addGSButton.setText("+");
        addGSButton.setToolTipText("Add Custom Ground Station");
        addGSButton.addActionListener(new java.awt.event.ActionListener()
        {
            public void actionPerformed(java.awt.event.ActionEvent evt)
            {
                addGSButtonActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(this);
        this.setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addComponent(jLabel1)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, 52, Short.MAX_VALUE)
                .addComponent(addGSButton))
            .addComponent(statusTextField, javax.swing.GroupLayout.DEFAULT_SIZE, 261, Short.MAX_VALUE)
            .addComponent(jScrollPane1, javax.swing.GroupLayout.DEFAULT_SIZE, 261, Short.MAX_VALUE)
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel1)
                    .addComponent(addGSButton))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jScrollPane1, javax.swing.GroupLayout.DEFAULT_SIZE, 329, Short.MAX_VALUE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(statusTextField, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
        );
    }// </editor-fold>//GEN-END:initComponents

    private void statusTextFieldActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_statusTextFieldActionPerformed
    {//GEN-HEADEREND:event_statusTextFieldActionPerformed
        // TODO add your handling code here:
}//GEN-LAST:event_statusTextFieldActionPerformed

    private void groundStationTreePropertyChange(java.beans.PropertyChangeEvent evt)//GEN-FIRST:event_groundStationTreePropertyChange
    {//GEN-HEADEREND:event_groundStationTreePropertyChange
        // TODO add your handling code here:
    }//GEN-LAST:event_groundStationTreePropertyChange

    private void groundStationTreeValueChanged(javax.swing.event.TreeSelectionEvent evt)//GEN-FIRST:event_groundStationTreeValueChanged
    {//GEN-HEADEREND:event_groundStationTreeValueChanged
        if(groundStationTree.getSelectionCount() > 0)
        {
            if( gsHash.containsKey(groundStationTree.getLastSelectedPathComponent().toString()) )
            {
                double[] lla = gsHash.get(groundStationTree.getLastSelectedPathComponent().toString());
                
                statusTextField.setText( "Lat:" + lla[0] + ", Lon:" + lla[1] +", Alt[m]:" + lla[2] );
            }
            else // clear text area
            {
                statusTextField.setText("");
            }
        } // something is selected
    }//GEN-LAST:event_groundStationTreeValueChanged

    private void addGSButtonActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_addGSButtonActionPerformed
    {//GEN-HEADEREND:event_addGSButtonActionPerformed
        JAddGroundStationDialog dlg = new JAddGroundStationDialog(parent,true);
        dlg.setVisible(true); // show dialog
        
        if(dlg.isOkHit())
        {
            // okay was hit process data
            String siteName = dlg.getSiteName();
            double latitude = dlg.getLatitude();
            double longitude = dlg.getLongitude();
            double altitude = dlg.getAltitude();
            boolean saveData = dlg.isSaveData();

            if (saveData) // append data to file
            {
                try
                {
                    repository.appendCustomStation(siteName, latitude, longitude, altitude);
                }
                catch (Exception e)
                {
                    System.out.println("ERROR SAVING GROUND STATION - Check permissions or format:");
                    e.printStackTrace();
                    JOptionPane.showMessageDialog(parent, "Error Saving Ground Station Data. Check data and permissions. \n" + e.toString(), "Data SAVING ERROR", JOptionPane.ERROR_MESSAGE);
                    return;
                }
            } // if save data

            String effectiveName = saveData
                    ? GroundStationCatalogRepository.sanitizeCsvField(siteName)
                    : siteName;

            // save ground station to hash
            gsHash.put(effectiveName, new double[]{latitude, longitude, altitude});

            // add new Ground station to the node
            DefaultMutableTreeNode newNode = new DefaultMutableTreeNode(effectiveName);
            treeModel.insertNodeInto(newNode, customSecondaryNode, customSecondaryNode.getChildCount());

            // expand custom
            groundStationTree.scrollPathToVisible(getPath(newNode));

        } // if okay hit
        
}//GEN-LAST:event_addGSButtonActionPerformed
    
    
    // Returns a TreePath containing the specified node.
    public TreePath getPath(TreeNode node)
    {
        List<TreeNode> list = new ArrayList<TreeNode>();

        // Add all nodes to list
        while (node != null)
        {
            list.add(node);
            node = node.getParent();
        }
        Collections.reverse(list);

        // Convert array of nodes to TreePath
        return new TreePath(list.toArray());
    }
    
    
    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton addGSButton;
    private javax.swing.JTree groundStationTree;
    private javax.swing.JLabel jLabel1;
    private javax.swing.JScrollPane jScrollPane1;
    private javax.swing.JTextField statusTextField;
    // End of variables declaration//GEN-END:variables
    
}
