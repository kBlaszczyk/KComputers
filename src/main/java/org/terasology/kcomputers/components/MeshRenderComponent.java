/*
 * Copyright 2018 MovingBlocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terasology.kcomputers.components;

import org.terasology.entitySystem.Component;
import org.terasology.entitySystem.entity.EntityBuilder;
import org.terasology.entitySystem.entity.EntityManager;
import org.terasology.entitySystem.entity.EntityRef;
import org.terasology.logic.location.LocationComponent;
import org.terasology.math.geom.Vector3f;
import org.terasology.rendering.logic.MeshComponent;

import java.util.HashMap;
import java.util.Map;

/**
 * Utility component used for holding a collection of mesh renderers.
 *
 * This is a bit of a workaround for directly missing functionality
 * in the Terasology engine as of writing. It should probably be replaced
 * with an in-engine solution for achieving the same thing
 * (dynamic mesh rendering in a block space).
 *
 * Generally, the intended use is to have unique keys for each
 * mesh entity (MeshComponent) you wish to render, and manage those
 * using the provided methods.
 */
public class MeshRenderComponent implements Component {
	private final Map<String, EntityRef> meshes = new HashMap<>();

	/**
	 * Dispose of a given mesh entity and its sub-elements.
	 * @param ref The EntityRef to dispose of.
	 * @return True if the EntityRef was disposed of.
 	 */
	private boolean dispose(EntityRef ref) {
		if (ref != null) {
			if (ref.getComponent(MeshComponent.class) != null) {
				ref.getComponent(MeshComponent.class).mesh.dispose();
				ref.getComponent(MeshComponent.class).material.dispose();
			}
			ref.destroy();
			return true;
		} else {
			return false;
		}
	}

	/**
	 * Remove a mesh entity with a given key.
	 * @param key The key of the mesh entity.
	 * @return Whether or not the removal was successful.
	 */
	public boolean remove(String key) {
		return dispose(meshes.remove(key));
	}

	/**
	 * Check if a mesh entity with a given key is present.
	 * @param key The key of the mesh entity.
	 * @return True if the mesh entity is present, false otherwise.
	 */
	public boolean has(String key) {
		return meshes.containsKey(key);
	}

	/**
	 * Get the MeshComponent of a mesh entity with a given key.
	 * @param key The key of the mesh entity.
	 * @return The MeshComponent by the given key, or null if no such key is present.
	 */
	public MeshComponent get(String key) {
		EntityRef ref = meshes.get(key);
		return ref != null ? ref.getComponent(MeshComponent.class) : null;
	}

	/**
	 * Add a new mesh entity.
	 *
	 * TODO: Currently, this will always clear the existing mesh entity.
	 * While sufficient for KallistiDisplayComponent usage, it should probably
	 * be improved depending on how well the engine reacts to MeshComponent changes.
	 *
	 * @param manager An EntityManager instance.
	 * @param key The key used for the mesh entity.
	 * @param location The location of the mesh entity.
	 * @param component The desired MeshComponent.
	 * @return True if the addition was successful, false otherwise.
	 */
	public boolean add(EntityManager manager, String key, Vector3f location, MeshComponent component) {
		EntityRef ref = meshes.get(key);
		if (ref != null) {
			if (remove(key)) {
				ref = null;
			} else {
				return false;
			}
		}

		EntityBuilder builder = manager.newBuilder();
		builder.setPersistent(false);
		builder.addComponent(new LocationComponent(location));
		builder.addComponent(component);
		ref = builder.build();
		meshes.put(key, ref);
		return true;
	}

	public void clear() {
		for (EntityRef ref : meshes.values()) {
			dispose(ref);
		}
		meshes.clear();
	}
}
