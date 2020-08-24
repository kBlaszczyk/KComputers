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

import com.google.common.primitives.UnsignedBytes;
import org.terasology.assets.ResourceUrn;
import org.terasology.assets.management.AssetManager;
import org.terasology.entitySystem.Component;
import org.terasology.entitySystem.entity.EntityManager;
import org.terasology.entitySystem.entity.EntityRef;
import org.terasology.kallisti.base.interfaces.FrameBuffer;
import org.terasology.kallisti.base.interfaces.Synchronizable;
import org.terasology.kallisti.base.util.Dimension;
import org.terasology.kallisti.oc.OCGPURenderer;
import org.terasology.kallisti.oc.OCTextRenderer;
import org.terasology.kcomputers.KComputersUtil;
import org.terasology.kcomputers.assets.HexFont;
import org.terasology.math.JomlUtil;
import org.terasology.math.Side;
import org.terasology.math.geom.Vector3f;
import org.terasology.network.NoReplicate;
import org.terasology.registry.CoreRegistry;
import org.terasology.rendering.assets.material.Material;
import org.terasology.rendering.assets.material.MaterialData;
import org.terasology.rendering.assets.mesh.MeshBuilder;
import org.terasology.rendering.assets.texture.Texture;
import org.terasology.rendering.assets.texture.TextureData;
import org.terasology.rendering.logic.MeshComponent;
import org.terasology.nui.Color;
import org.terasology.utilities.Assets;
import org.terasology.world.block.BlockComponent;
import org.terasology.world.block.BlockPart;
import org.terasology.world.block.shapes.BlockMeshPart;
import org.terasology.world.block.shapes.BlockShape;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Internal Kallisti display component. It is instantiated a single time in
 * either the single-block entity providing a KallistiDisplayCandidateComponent,
 * or a "master" multi-block entity for multi-block monitors. Candidates then
 * function as an effective many-to-one mapping to the "main" component.
 *
 * LIMITATIONS: KallistiDisplayComponent currently only supports a single image
 * being blit to it simultaneously. This is fine for as long as Kallisti renderers
 * do exclusively that.
 *
 * @see KallistiDisplayCandidateComponent
 */
@NoReplicate
public class KallistiDisplayComponent implements Component, FrameBuffer, Synchronizable.Receiver {
	private static final String DISPLAY_KEY = "display";

	private transient EntityManager entityManager;
	private transient EntityRef self;
	private transient KallistiDisplayCandidateComponent candidate;
	private transient MeshRenderComponent mesh;

	/**
	 * Configure the display component.
	 *
	 * @param entityManager The EntityManager instance.
	 * @param self Reference to entity which stores the MeshRenderComponent instance for rendering.
	 * @param candidate The KallistiDisplayCandidateComponent instance to derive configuration from.
	 * @param mesh The MeshRenderComponent instance to use for rendering.
	 */
	public void configure(EntityManager entityManager, EntityRef self, KallistiDisplayCandidateComponent candidate, MeshRenderComponent mesh) {
		this.entityManager = entityManager;
		this.self = self;
		this.candidate = candidate;
		this.mesh = mesh;

		candidate.setDisplay(this);
	}

	private transient Synchronizable source;
	private transient Renderer renderer;
	private transient Texture texture;
	private transient int pw, ph;

	public Synchronizable getSource() {
		return source;
	}

	@Override
	public void bind(Synchronizable source, Renderer renderer) {
		this.source = source;
		this.renderer = renderer;
	}

	@Override
	public Dimension aspectRatio() {
		return new Dimension(1, 1);
	}

	private transient ByteBuffer dataBB;

	@Override
	public void finalize() {
		dataBB.clear();
	}

	@Override
	public void blit(Image image) {
		MeshComponent component;

		if (dataBB == null || dataBB.capacity() != 4 * image.size().getX() * image.size().getY()) {
			if (dataBB != null) {
				dataBB.clear();
			}
			dataBB = ByteBuffer.allocateDirect(4 * image.size().getX() * image.size().getY());
		}

		for (int argb : image.data()) {
			int r = (argb >> 16) & 0xFF;
			int g = (argb >> 8) & 0xFF;
			int b = argb & 0xFF;
			dataBB.put(UnsignedBytes.checkedCast(r));
			dataBB.put(UnsignedBytes.checkedCast(g));
			dataBB.put(UnsignedBytes.checkedCast(b));
			dataBB.put((byte) 0xFF);
		}

		dataBB.rewind();

		pw = image.size().getX();
		ph = image.size().getY();
		texture = Assets.generateAsset(new TextureData(image.size().getX(), image.size().getY(),
			new ByteBuffer[]{ dataBB }, Texture.WrapMode.REPEAT, Texture.FilterMode.NEAREST), Texture.class);

		MaterialData terrainMatData = new MaterialData(Assets.getShader("engine:genericMeshMaterial").get());
		terrainMatData.setParam("diffuse", texture);
		terrainMatData.setParam("colorOffset", new float[]{1, 1, 1});
		terrainMatData.setParam("textured", true);
		Material material = Assets.generateAsset(terrainMatData, Material.class);

		component = mesh.get(DISPLAY_KEY);
		if (component != null) {
			component.material.dispose();
			component.material = material;
		} else {
			component = new MeshComponent();
			component.material = material;

			Vector3f location = self.getComponent(BlockComponent.class).getPosition().toVector3f().add(0.5f, 0.5f, 0.5f);
			Side side = self.getComponent(BlockComponent.class).getBlock().getDirection();
			if (side == null) side = Side.TOP;

			MeshBuilder meshBuilder = new MeshBuilder();
			BlockShape blockShape = Assets.get("engine:cube", BlockShape.class).get();
			BlockMeshPart meshPart = blockShape.getMeshPart(BlockPart.fromSide(side));

			for (int i = 0; i < meshPart.indicesSize(); i++) {
				meshBuilder.addIndices(meshPart.getIndex(i));
			}

			for (int i = 0; i < meshPart.size(); i++) {
				Vector3f v = new Vector3f(JomlUtil.from(meshPart.getVertex(i)));
				// reduce by border size
				Vector3f reduction = new Vector3f(
						1 - (candidate.borderThickness * 2 * (1 - Math.abs(side.getVector3i().x))),
						1 - (candidate.borderThickness * 2 * (1 - Math.abs(side.getVector3i().y))),
						1 - (candidate.borderThickness * 2 * (1 - Math.abs(side.getVector3i().z)))
				);

				// bring forward to avoid Z-fighting
				v.mul(reduction.x, reduction.y, reduction.z).add(side.getVector3i().toVector3f().mul(0.01f));

				meshBuilder.addVertex(v.sub(.5f, .5f, .5f));
				meshBuilder.addColor(Color.WHITE);
				meshBuilder.addTexCoord(JomlUtil.from(meshPart.getTexCoord(i)));
			}

			component.mesh = meshBuilder.build();
			component.translucent = false;
			component.hideFromOwner = false;
			component.color = Color.WHITE;

			mesh.add(entityManager, DISPLAY_KEY, new Vector3f(location), component);
		}

		self.saveComponent(mesh);
	}

	private void initRenderer() {
		if (renderer == null) {
			try {
				// TODO: Add Kallisti-side API for instantiating renderers
				// based on the KallistiSyncInitial packet received.
				renderer = new OCGPURenderer(
						new OCTextRenderer(
								CoreRegistry.get(AssetManager.class)
								.getAsset(new ResourceUrn("KComputers:unicode-8x16"), HexFont.class)
								.get().getKallistiFont()
						)
				);
			} catch (Exception e) {
				KComputersUtil.LOGGER.warn("Error initializing display renderer!", e);
			}
		}
	}

	public void render() {
		initRenderer();
		renderer.render(this);
	}

	@Override
	public void update(InputStream stream) throws IOException {
		initRenderer();

		if (renderer != null) {
			renderer.update(stream);
			render();
		}
	}

	/**
	 * @return The texture to be rendered.
	 */
	@Nullable
	public Texture getTexture() {
		return texture;
	}

	/**
	 * @return The width of the texture to be rendered, in pixels.
	 */
	public int getPixelWidth() {
		return pw;
	}

	/**
	 * @return The height of the texture to be rendered, in pixels.
	 */
	public int getPixelHeight() {
		return ph;
	}

	/**
	 * FIXME: A slight hack used to get the KallistiKeyboardComponent instance
	 * in ComputerDisplayWidget.
	 *
	 * @see org.terasology.kcomputers.rendering.nui.layers.ComputerDisplayWidget
	 * @return The reference to the display "self" entity.
	 */
	public EntityRef getEntityRef() {
		return self;
	}
}
