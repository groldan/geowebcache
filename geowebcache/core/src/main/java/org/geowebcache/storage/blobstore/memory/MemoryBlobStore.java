/**
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * <p>This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * <p>You should have received a copy of the GNU Lesser General Public License along with this
 * program. If not, see <http://www.gnu.org/licenses/>.
 *
 * <p>Copyright 2019
 */
package org.geowebcache.storage.blobstore.memory;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.geotools.util.logging.Logging;
import org.geowebcache.io.ByteArrayResource;
import org.geowebcache.io.Resource;
import org.geowebcache.storage.BlobStore;
import org.geowebcache.storage.BlobStoreDecorator;
import org.geowebcache.storage.BlobStoreListener;
import org.geowebcache.storage.StorageException;
import org.geowebcache.storage.TileObject;
import org.geowebcache.storage.TileRange;
import org.geowebcache.storage.blobstore.memory.guava.GuavaCacheProvider;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

/**
 * This class is an implementation of the {@link BlobStore} interface wrapping another {@link
 * BlobStore} implementation and supporting in memory caching. Caching is provided by an input
 * {@link CacheProvider} object. It must be pointed out that this Blobstore has an asynchronous
 * relation with the underlying wrapped {@link BlobStore}. In fact, each operation on the wrapped
 * {@link BlobStore} is scheduled in a queue and will be done by an executor thread. Operations that
 * require a boolean value will have to wait until previous tasks are completed.
 *
 * @author Nicola Lagomarsini Geosolutions
 */
public class MemoryBlobStore extends BlobStoreDecorator
        implements BlobStore, ApplicationContextAware {

    /** {@link Log} object used for logging exceptions */
    private static final Logger log = Logging.getLogger(MemoryBlobStore.class.getName());

    /** {@link CacheProvider} object to use for caching */
    private CacheProvider cacheProvider;

    /**
     * Optional name used for searching the bean related to the CacheProvider to set in the
     * ApplicationContext
     */
    private String cacheBeanName;

    /** Boolean used for Application Context initialization */
    private final AtomicBoolean cacheAlreadySet = new AtomicBoolean();

    public MemoryBlobStore() {
        // Initialization of the various elements
        // Initialization of the cacheProvider and store. Must be overridden, this uses default and
        // caches in memory
        setStore(new NullBlobStore());
        GuavaCacheProvider startingCache = new GuavaCacheProvider(new CacheConfiguration());
        this.cacheProvider = startingCache;
    }

    @Override
    public boolean delete(String layerName) throws StorageException {
        try {
            cacheProvider.removeLayer(layerName);
            return super.delete(layerName);
        } finally {
            cacheProvider.removeLayer(layerName);
        }
    }

    @Override
    public boolean deleteByGridsetId(String layerName, String gridSetId) throws StorageException {
        try {
            cacheProvider.removeLayer(layerName);
            return super.deleteByGridsetId(layerName, gridSetId);
        } finally {
            cacheProvider.removeLayer(layerName);
        }
    }

    @Override
    public boolean delete(TileObject obj) throws StorageException {
        try {
            return super.delete(obj);
        } finally {
            cacheProvider.removeTileObj(obj);
        }
    }

    @Override
    public boolean delete(TileRange obj) throws StorageException {
        cacheProvider.removeLayer(obj.getLayerName());
        // call delegate.delete(TileRange) and let it notify the listener to evict any subsequent
        // cached tile
        return super.delete(obj);
    }

    @Override
    public boolean get(TileObject obj) throws StorageException {
        if (log.isLoggable(Level.FINE)) {
            log.fine("Checking if TileObject:" + obj + " is present");
        }
        TileObject cached = cacheProvider.getTileObj(obj);
        boolean found = null != cached;
        if (!found) {
            if (log.isLoggable(Level.FINE)) {
                log.fine(
                        "TileObject:"
                                + obj
                                + " not found. Try to get it from the wrapped blobstore");
            }
            found = super.get(obj);

            // If the file has been found, it is inserted in cacheProvider
            if (found) {
                if (log.isLoggable(Level.FINE)) {
                    log.fine("TileObject:" + obj + " found. Put it in cache");
                }
                // Get the Cached TileObject
                cached = getByteResourceTile(obj);
                // Put the file in Cache
                cacheProvider.putTileObj(cached);
            }
        }
        // If found add its resource to the input TileObject
        if (found) {
            if (log.isLoggable(Level.FINE)) {
                log.fine("TileObject:" + obj + " found, update the input TileObject");
            }
            Resource resource = cached.getBlob();
            obj.setBlob(resource);
            obj.setCreated(resource.getLastModified());
            obj.setBlobSize((int) resource.getSize());
        }

        return found;
    }

    @Override
    public void put(TileObject obj) throws StorageException {
        super.put(obj);
        TileObject cached = getByteResourceTile(obj);
        cacheProvider.putTileObj(cached);
    }

    @Override
    public void clear() throws StorageException {
        try {
            super.clear();
        } finally {
            cacheProvider.clear();
        }
    }

    @Override
    public void destroy() {
        try {
            super.destroy();
        } finally {
            cacheProvider.reset();
        }
    }

    @Override
    public boolean rename(String oldLayerName, String newLayerName) throws StorageException {
        boolean renamed = false;
        try {
            renamed = super.rename(oldLayerName, newLayerName);
        } finally {
            if (renamed) cacheProvider.removeLayer(oldLayerName);
        }
        return renamed;
    }

    /** @return a {@link CacheStatistics} object containing the {@link CacheProvider} statistics */
    public CacheStatistics getCacheStatistics() {
        return cacheProvider.getStatistics();
    }

    /** Setter for the cacheProvider to use */
    public void setCacheProvider(CacheProvider cache) {
        Objects.requireNonNull(cache, "Input BlobStore cannot be null");
        this.cacheProvider = cache;
        cacheAlreadySet.set(true);
    }

    /**
     * * This method is used for converting a {@link TileObject} {@link Resource} into a {@link
     * ByteArrayResource}.
     *
     * @return a TileObject with resource stored in a Byte Array
     */
    private TileObject getByteResourceTile(TileObject obj) throws StorageException {
        // Get TileObject resource
        Resource blob = obj.getBlob();
        final ByteArrayResource finalBlob;
        // If it is a ByteArrayResource, the result is simply copied
        if (obj.getBlob() instanceof ByteArrayResource) {
            if (log.isLoggable(Level.FINE)) {
                log.fine("Resource is already a Byte Array, only a copy is needed");
            }
            ByteArrayResource byteArrayResource = (ByteArrayResource) obj.getBlob();
            byte[] contents = byteArrayResource.getContents();
            finalBlob = new ByteArrayResource(contents);
        } else {
            if (log.isLoggable(Level.FINE)) {
                log.fine("Resource is not a Byte Array, data must be transferred");
            }
            // Else the result is written to a new WritableByteChannel
            try (ByteArrayOutputStream bOut = new ByteArrayOutputStream();
                    WritableByteChannel wChannel = Channels.newChannel(bOut)) {
                blob.transferTo(wChannel);
                finalBlob = new ByteArrayResource(bOut.toByteArray());
            } catch (IOException e) {
                throw new StorageException(e.getLocalizedMessage(), e);
            }
        }

        finalBlob.setLastModified(blob.getLastModified());
        // Creation of a new Resource
        TileObject cached =
                TileObject.createCompleteTileObject(
                        obj.getLayerName(),
                        obj.getXYZ(),
                        obj.getGridSetId(),
                        obj.getBlobFormat(),
                        obj.getParameters(),
                        finalBlob);
        return cached;
    }

    /**
     * Setter for the Cache Provider name, note that this cannot be used in combination with the
     * setCacheProvider method in the application Context initialization
     */
    public void setCacheBeanName(String cacheBeanName) {
        this.cacheBeanName = cacheBeanName;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        if (!cacheAlreadySet.get()) {
            // Get all the CacheProvider beans
            String[] beans = applicationContext.getBeanNamesForType(CacheProvider.class);
            int beanSize = beans.length;
            boolean configured = false;
            // If at least one bean is present, use it
            if (beanSize > 0) {
                // If a bean name is defined, get the related bean
                if (cacheBeanName != null && !cacheBeanName.isEmpty()) {
                    for (String beanDef : beans) {
                        if (cacheBeanName.equalsIgnoreCase(beanDef)) {
                            CacheProvider bean =
                                    applicationContext.getBean(beanDef, CacheProvider.class);
                            if (bean.isAvailable()) {
                                setCacheProvider(bean);
                                configured = true;
                                break;
                            }
                        }
                    }
                }
                // If only one is present it is used
                if (!configured && beanSize == 1) {
                    CacheProvider bean = applicationContext.getBean(beans[0], CacheProvider.class);
                    if (bean.isAvailable()) {
                        setCacheProvider(bean);
                        configured = true;
                    }
                }
                // If two are present and at least one of them is not guava, then it is used
                if (!configured && beanSize == 2) {
                    for (String beanDef : beans) {
                        CacheProvider bean =
                                applicationContext.getBean(beanDef, CacheProvider.class);
                        if (!(bean instanceof GuavaCacheProvider) && bean.isAvailable()) {
                            setCacheProvider(bean);
                            configured = true;
                            break;
                        }
                    }
                    // Try again and search if at least a GuavaCacheProvider is present
                    if (!configured) {
                        for (String beanDef : beans) {
                            CacheProvider bean =
                                    applicationContext.getBean(beanDef, CacheProvider.class);
                            if (bean.isAvailable()) {
                                setCacheProvider(bean);
                                configured = true;
                                break;
                            }
                        }
                    }
                }
                if (!configured) {
                    if (log.isLoggable(Level.FINE)) {
                        log.fine("CacheProvider not configured, use default configuration");
                    }
                }
            }
        } else {
            if (log.isLoggable(Level.FINE)) {
                log.fine("CacheProvider already configured");
            }
        }
    }

    @Override
    public boolean deleteByParametersId(String layerName, String parametersId)
            throws StorageException {

        try {
            return super.deleteByGridsetId(layerName, parametersId);
        } finally {
            cacheProvider.removeLayer(layerName);
        }
    }

    private static final class MemoryBlobStoreCleanUpListener implements BlobStoreListener {

        private CacheProvider cacheProvider;

        @Override
        public void tileStored(
                String layerName,
                String gridSetId,
                String blobFormat,
                String parametersId,
                long x,
                long y,
                int z,
                long blobSize) {
            // no-op
        }

        @Override
        public void tileDeleted(
                String layerName,
                String gridSetId,
                String blobFormat,
                String parametersId,
                long x,
                long y,
                int z,
                long blobSize) {

            removeTile(layerName, gridSetId, blobFormat, parametersId, x, y, z);
        }

        @Override
        public void tileUpdated(
                String layerName,
                String gridSetId,
                String blobFormat,
                String parametersId,
                long x,
                long y,
                int z,
                long blobSize,
                long oldSize) {
            removeTile(layerName, gridSetId, blobFormat, parametersId, x, y, z);
        }

        private void removeTile(
                String layerName,
                String gridSetId,
                String blobFormat,
                String parametersId,
                long x,
                long y,
                int z) {
            long[] coords = new long[] {x, y, z};
            TileObject tile =
                    TileObject.createQueryTileObject(
                            layerName, coords, gridSetId, blobFormat, Map.of());
            tile.setParametersId(parametersId);
            cacheProvider.removeTileObj(tile);
        }

        @Override
        public void layerDeleted(String layerName) {
            cacheProvider.removeLayer(layerName);
        }

        @Override
        public void layerRenamed(String oldLayerName, String newLayerName) {
            cacheProvider.removeLayer(oldLayerName);
        }

        @Override
        public void gridSubsetDeleted(String layerName, String gridSetId) {
            cacheProvider.removeLayer(layerName);
        }

        @Override
        public void parametersDeleted(String layerName, String parametersId) {
            cacheProvider.removeLayer(layerName);
        }
    }
}
