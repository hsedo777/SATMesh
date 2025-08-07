package org.sedo.satmesh.ui.adapter;

import static org.sedo.satmesh.ui.adapter.NearbyDiscoveryAdapter.NodeDiscoveryViewHolder;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import org.sedo.satmesh.R;
import org.sedo.satmesh.databinding.ItemNearbyNodeBinding;
import org.sedo.satmesh.ui.data.NodeDiscoveryItem;
import org.sedo.satmesh.ui.data.NodeState;

import java.util.Objects;

public class NearbyDiscoveryAdapter extends ListAdapter<NodeDiscoveryItem, NodeDiscoveryViewHolder> {

	private final OnChildClickCallback callback;
	private OnNodeClickListener clickListener;

	public NearbyDiscoveryAdapter() {
		super(new NearbyDiscoveryDiffUtil());
		callback = new OnChildClickCallback() {
			@Override
			public void onClick(int position) {
				if (clickListener != null) {
					NodeDiscoveryItem item = getItem(position);
					clickListener.onClick(item);
				}
			}

			@Override
			public void onLongClick(int position) {
				if (clickListener != null) {
					NodeDiscoveryItem item = getItem(position);
					clickListener.onLongClick(item);
				}
			}
		};
	}

	public void attachOnNodeClickListener(@NonNull OnNodeClickListener listener) {
		this.clickListener = listener;
	}

	@NonNull
	@Override
	public NodeDiscoveryViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		ItemNearbyNodeBinding binding = ItemNearbyNodeBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
		return new NodeDiscoveryViewHolder(binding, callback);
	}

	@Override
	public void onBindViewHolder(@NonNull NodeDiscoveryViewHolder holder, int position) {
		NodeDiscoveryItem item = getItem(position);
		holder.bind(item);
	}

	public interface OnNodeClickListener {
		void onClick(@NonNull NodeDiscoveryItem item);

		void onLongClick(@NonNull NodeDiscoveryItem item);
	}

	public interface OnChildClickCallback {
		void onClick(int position);

		void onLongClick(int position);
	}

	public static class NodeDiscoveryViewHolder extends RecyclerView.ViewHolder {
		private final ItemNearbyNodeBinding itemBinding;

		public NodeDiscoveryViewHolder(@NonNull ItemNearbyNodeBinding item, @NonNull OnChildClickCallback onClick) {
			super(item.getRoot());
			this.itemBinding = item;
			this.itemView.setOnClickListener(view -> {
				try {
					onClick.onClick(getAdapterPosition());
				} catch (Exception ignored) {
				}
			});
			this.itemView.setOnLongClickListener(view -> {
				try {
					onClick.onLongClick(getAdapterPosition());
				} catch (Exception ignored) {
				}
				return true;
			});
		}

		public void bind(@NonNull NodeDiscoveryItem item) {
			itemBinding.nodeDisplayName.setText(item.getNonNullName());
			int color = ContextCompat.getColor(itemView.getContext(), item.state().getColorResId());
			itemBinding.statusIndicator.getBackground().setTint(color);
			if (item.state() == NodeState.ON_CONNECTED) {
				int sbg, textCode;
				if (item.isSecured()) {
					sbg = ContextCompat.getColor(itemView.getContext(), R.color.ic_lock_secure);
					textCode = R.string.status_secure_session_active;
				} else {
					sbg = ContextCompat.getColor(itemView.getContext(), R.color.ic_lock_not_secure);
					textCode = R.string.status_waiting_to_send_our_keys;
				}
				itemBinding.secureSessionStatus.setText(textCode);
				itemBinding.secureSessionIcon.getBackground().setTint(sbg);
				itemBinding.itemSecureSessionContainer.setVisibility(View.VISIBLE);
			} else {
				itemBinding.itemSecureSessionContainer.setVisibility(View.GONE);
			}
		}
	}

	public static class NearbyDiscoveryDiffUtil extends DiffUtil.ItemCallback<NodeDiscoveryItem> {

		@Override
		public boolean areItemsTheSame(@NonNull NodeDiscoveryItem oldItem, @NonNull NodeDiscoveryItem newItem) {
			return Objects.equals(oldItem.getAddressName(), newItem.getAddressName());
		}

		@Override
		public boolean areContentsTheSame(@NonNull NodeDiscoveryItem oldItem, @NonNull NodeDiscoveryItem newItem) {
			return oldItem.equals(newItem);
		}
	}
}
