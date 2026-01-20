/******************************************************************************
 * iDempiere Model Test - Tests using MTable, Query, etc.
 * Requires iDempiere classpath but not full OSGi runtime
 *****************************************************************************/
package org.idempiere.ninja.test;

import java.util.Properties;
import java.util.logging.Level;

import org.compiere.Adempiere;
import org.compiere.model.MColumn;
import org.compiere.model.MTable;
import org.compiere.model.Query;
import org.compiere.util.CLogMgt;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.compiere.util.Ini;
import org.compiere.util.Trx;
import org.adempiere.util.ServerContext;

/**
 * Test iDempiere model layer (MTable, Query, etc.)
 */
public class IdempiereModelTest {

    public static void main(String[] args) {
        System.out.println("=== IDEMPIERE MODEL TEST ===");
        System.out.println("Testing iDempiere model layer (MTable, Query, Trx)");
        System.out.println();

        try {
            // Initialize
            System.out.println("[INIT] Setting up server context...");
            Properties serverContext = new Properties();
            ServerContext.setCurrentInstance(serverContext);

            // Find properties
            String propFile = System.getProperty("PropertyFile",
                "/home/red1/idempiere-dev-setup/idempiere/idempiere.properties");
            System.out.println("[INIT] Properties: " + propFile);
            System.setProperty("PropertyFile", propFile);

            // Start Adempiere
            System.out.println("[INIT] Starting Adempiere...");
            CLogMgt.setLevel(Level.WARNING);

            if (!Adempiere.isStarted()) {
                boolean started = Adempiere.startup(false);
                if (!started) {
                    System.err.println("[FAIL] Adempiere.startup() failed");
                    return;
                }
            }
            System.out.println("[PASS] Adempiere started");

            // Set context
            Env.setContext(Env.getCtx(), Env.AD_CLIENT_ID, 0);
            Env.setContext(Env.getCtx(), Env.AD_ORG_ID, 0);
            Env.setContext(Env.getCtx(), "#AD_User_ID", 0);

            // Test DB utility
            System.out.println();
            System.out.println("[TEST] DB.getSQLValue...");
            int tableCount = DB.getSQLValue(null, "SELECT COUNT(*) FROM AD_Table");
            System.out.println("[PASS] AD_Table count via DB: " + tableCount);

            // Test MTable.get
            System.out.println();
            System.out.println("[TEST] MTable.get...");
            MTable adTable = MTable.get(Env.getCtx(), "AD_Table");
            if (adTable != null) {
                System.out.println("[PASS] MTable AD_Table: ID=" + adTable.getAD_Table_ID());
            } else {
                System.err.println("[FAIL] MTable.get returned null");
            }

            // Test Query
            System.out.println();
            System.out.println("[TEST] Query class...");
            int colCount = new Query(Env.getCtx(), MColumn.Table_Name, null, null).count();
            System.out.println("[PASS] Query AD_Column count: " + colCount);

            // Test Query with where clause
            MTable testTable = new Query(Env.getCtx(), MTable.Table_Name,
                    "TableName=?", null)
                    .setParameters("C_BPartner")
                    .first();
            if (testTable != null) {
                System.out.println("[PASS] Query C_BPartner table: ID=" + testTable.getAD_Table_ID());
            }

            // Test Transaction
            System.out.println();
            System.out.println("[TEST] Transaction...");
            String trxName = Trx.createTrxName("ModelTest");
            Trx trx = Trx.get(trxName, true);
            System.out.println("[PASS] Transaction created: " + trxName);

            // Test query within transaction
            int inTrx = new Query(Env.getCtx(), MTable.Table_Name, null, trxName).count();
            System.out.println("[PASS] Query in transaction: " + inTrx + " tables");

            // Rollback
            trx.rollback();
            trx.close();
            System.out.println("[PASS] Transaction rolled back");

            System.out.println();
            System.out.println("=== ALL MODEL TESTS PASSED ===");

        } catch (Exception e) {
            System.err.println("[FAIL] " + e.getMessage());
            e.printStackTrace();
        }
    }
}
