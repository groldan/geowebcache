package org.geowebcache.filter.request;

import org.geowebcache.GeoWebCacheException;
import org.geowebcache.conveyor.ConveyorTile;
import org.geowebcache.grid.BoundingBox;
import org.geowebcache.grid.GridSubset;
import org.geowebcache.grid.SRS;
import org.geowebcache.layer.TileLayer;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.LinearRing;
import com.vividsolutions.jts.geom.Polygon;

public class GeometryFilter extends RequestFilter {

    private static final long serialVersionUID = -8363685969403664933L;

    private String CRS;

    private boolean checkIntersection;

    private boolean axisOrderNorthEast;

    private Geometry geometry;

    public GeometryFilter() {
        readResolve();
    }

    private GeometryFilter readResolve() {
        return this;
    }

    public String getCRS() {
        return CRS;
    }

    public void setCRS(String cRS) {
        CRS = cRS;
    }

    public Geometry getGeometry() {
        return geometry;
    }

    public void setGeometry(Geometry geometry) {
        this.geometry = geometry;
    }

    /**
     * @return {@code true} if the requested tile bounds are checked for intersection against this
     *         filter's geometry, {@code false} if checked for containment instead. Defaults to
     *         {@code false}.
     */
    public boolean isCheckIntersection() {
        return checkIntersection;
    }

    /**
     * @param checkIntersection {@code true} if the requested tile bounds are to be checked for
     *        intersection against this filter's geometry, {@code false} if the check is for
     *        containment instead. Defaults to {@code false}.
     */
    public void setCheckIntersection(boolean checkIntersection) {
        this.checkIntersection = checkIntersection;
    }

    /**
     * @return {@code true} if this filter's geometry axis order is North/East (Y/X), defaults to
     *         {@code false}
     */
    public boolean isAxisOrderNorthEast() {
        return axisOrderNorthEast;
    }

    /**
     * @param axisOrderNorthEast {@code true} if this filter's geometry axis order is North/East
     *        (Y/X), defaults to {@code false}
     */
    public void setAxisOrderNorthEast(boolean axisOrderNorthEast) {
        this.axisOrderNorthEast = axisOrderNorthEast;
    }

    @Override
    public void initialize(TileLayer layer) throws GeoWebCacheException {
        // nothing to do, REVISIT design
    }

    @Override
    public boolean update(TileLayer layer, String gridSetId) {
        // nothing to do, REVISIT design
        return true;
    }

    @Override
    public void update(TileLayer layer, String gridSetId, int zoomStart, int zoomStop)
            throws GeoWebCacheException {
        // nothing to do, REVISIT design
    }

    @Override
    public void update(byte[] filterData, TileLayer layer, String gridSetId, int z)
            throws GeoWebCacheException {
        // nothing to do, REVISIT design
    }

    @Override
    public void apply(ConveyorTile convTile) throws RequestFilterException {
        if (this.geometry == null) {
            return;
        }
        if (this.CRS == null) {
            return;
        }
        final String gridSetId = convTile.getGridSetId();
        final TileLayer tileLayer = convTile.getTileLayer();
        final GridSubset gridSubset = tileLayer.getGridSubset(gridSetId);
        final SRS requestSrs = gridSubset.getGridSet().getSrs();
        final SRS filterSrs;
        try {
            filterSrs = SRS.getSRS(this.CRS);
        } catch (GeoWebCacheException e) {
            throw new IllegalStateException("Unknonwn filter CRS: " + CRS, e);
        }
        if (!requestSrs.equals(filterSrs)) {
            return;
        }

        final long[] tileIndex = convTile.getTileIndex();

        boolean applies = checkFilterApplies(gridSubset, tileIndex);
        if (!applies) {
            throw new BlankTileException(this);
        }
    }

    private boolean checkFilterApplies(final GridSubset gridSubset, final long[] tileIndex) {
        final BoundingBox tileBounds = gridSubset.boundsFromIndex(tileIndex);
        double minx = axisOrderNorthEast ? tileBounds.getMinY() : tileBounds.getMinX();
        double miny = axisOrderNorthEast ? tileBounds.getMinX() : tileBounds.getMinY();
        double maxx = axisOrderNorthEast ? tileBounds.getMaxY() : tileBounds.getMaxX();
        double maxy = axisOrderNorthEast ? tileBounds.getMaxX() : tileBounds.getMaxY();

        GeometryFactory factory = this.geometry.getFactory();
        LinearRing exterior = factory.createLinearRing(//
                new Coordinate[] { new Coordinate(minx, miny),//
                        new Coordinate(minx, maxy), //
                        new Coordinate(maxx, maxy), //
                        new Coordinate(maxx, miny), //
                        new Coordinate(minx, miny) //
                });
        Polygon tilePolygon = factory.createPolygon(exterior, (LinearRing[]) null);

        boolean applies = checkIntersection ? geometry.intersects(tilePolygon) : geometry
                .contains(tilePolygon);
        return applies;
    }
}
