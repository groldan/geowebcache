package org.geowebcache.storage;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class BlobStoreDecorator implements BlobStore {

    protected BlobStore delegate;

    protected BlobStoreDecorator() {
        // no-op, delegate shall be set through setStore()
    }

    protected BlobStoreDecorator(BlobStore delegate) {
        setStore(delegate);
    }

    public void setStore(BlobStore delegate) {
        Objects.requireNonNull(delegate);
        this.delegate = delegate;
    }

    /** @return The wrapped {@link BlobStore} implementation */
    public BlobStore getStore() {
        return delegate;
    }

    @Override
    public boolean delete(String layerName) throws StorageException {
        return delegate.delete(layerName);
    }

    @Override
    public boolean deleteByGridsetId(String layerName, String gridSetId) throws StorageException {
        return delegate.deleteByGridsetId(layerName, gridSetId);
    }

    @Override
    public boolean deleteByParametersId(String layerName, String parametersId)
            throws StorageException {
        return delegate.deleteByParametersId(layerName, parametersId);
    }

    @Override
    public boolean delete(TileObject obj) throws StorageException {
        return delegate.delete(obj);
    }

    @Override
    public boolean delete(TileRange obj) throws StorageException {
        return delegate.delete(obj);
    }

    @Override
    public boolean get(TileObject obj) throws StorageException {
        return delegate.get(obj);
    }

    @Override
    public void put(TileObject obj) throws StorageException {
        delegate.put(obj);
    }

    @Override
    public void clear() throws StorageException {
        delegate.clear();
    }

    @Override
    public void destroy() {
        delegate.destroy();
    }

    @Override
    public void addListener(BlobStoreListener listener) {
        delegate.addListener(listener);
    }

    @Override
    public boolean removeListener(BlobStoreListener listener) {
        return delegate.removeListener(listener);
    }

    @Override
    public boolean rename(String oldLayerName, String newLayerName) throws StorageException {
        return delegate.rename(oldLayerName, newLayerName);
    }

    @Override
    public String getLayerMetadata(String layerName, String key) {
        return delegate.getLayerMetadata(layerName, key);
    }

    @Override
    public void putLayerMetadata(String layerName, String key, String value) {
        delegate.putLayerMetadata(layerName, key, value);
    }

    @Override
    public boolean layerExists(String layerName) {
        return delegate.layerExists(layerName);
    }

    @Override
    public Map<String, Optional<Map<String, String>>> getParametersMapping(String layerName) {
        return delegate.getParametersMapping(layerName);
    }
}
