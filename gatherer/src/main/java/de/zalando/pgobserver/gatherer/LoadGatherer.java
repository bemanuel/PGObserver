package de.zalando.pgobserver.gatherer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author  jmussler
 */
public class LoadGatherer extends ADBGatherer {

    // used to store values until storage db is available again
    // could have used linked list for pop, but decided for arraylist due to space reasons in case of prolonged connection problems.
    private final List<LoadStatsValue> valueStore = new ArrayList<LoadStatsValue>();
    
    public static final Logger LOG = Logger.getLogger(LoadGatherer.class.getName());

    public LoadGatherer(final Host h, final long interval, final ScheduledThreadPoolExecutor ex) {
        super(h, ex, interval);
    }

    @Override
    protected boolean gatherData() {
        Connection conn = null;
        try {
            conn = DriverManager.getConnection("jdbc:postgresql://" + host.name + ":" + host.port + "/" + host.dbname,
                    host.user, host.password);

            Statement st = conn.createStatement();
            st.execute("SET statement_timeout TO '5s';");

            long time = System.currentTimeMillis();

            LoadStatsValue v = null;

            ResultSet rs = st.executeQuery("SELECT * FROM zz_utils.get_load_average() t( min1, min5, min15 );");

            if (rs.next()) {
                v = new LoadStatsValue(time, Math.round( rs.getFloat("min1") * 100), Math.round(rs.getFloat("min5") * 100),
                        Math.round(rs.getFloat("min15") * 100));
                valueStore.add(v);
            }

            rs.close();
            conn.close(); // we close here, because we are done

            conn = null;

            if (!valueStore.isEmpty()) {

                Logger.getLogger(SprocGatherer.class.getName()).log(Level.INFO, "[{0}] finished getting sproc data",
                    host.name);

                conn = DBPools.getDataConnection();                

                PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO monitor_data.host_load(load_timestamp,load_host_id , load_1min_value, load_5min_value, load_15min_value ) VALUES (?, ?, ?, ?, ?);");

                while ( !valueStore.isEmpty()) {                    
                                        
                    v = valueStore.remove(valueStore.size()-1);
                
                    ps.setTimestamp(1, new Timestamp( v.timestamp ) );
                    ps.setInt(2, host.id);
                    ps.setLong(3, v.load_1min);
                    ps.setLong(4, v.load_5min);
                    ps.setLong(5, v.load_15min);
                    ps.execute();
                
                }

                ps.close();
                conn.close();
                conn = null;

                LOG.log(Level.INFO, "[{0}] current load value stored",
                    this.getName());
                
            } else {
                LOG.log(Level.WARNING,
                    "[{0}] could not retrieve load value!", this.getName());
            }

            return true;
        } catch (SQLException se) {
            LOG.log(Level.SEVERE, "", se);
            return false;
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException ex) {
                    LOG.log(Level.SEVERE, "", ex);
                }
            }
        }
    }
}
