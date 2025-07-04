package com.lsoysapp.callresponderuser;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.card.MaterialCardView;
import java.util.List;

public class SubscriptionAdapter extends RecyclerView.Adapter<SubscriptionAdapter.SubscriptionViewHolder> {

    private List<SubscriptionPlan> subscriptionPlans;
    private OnSubscriptionClickListener listener;

    public interface OnSubscriptionClickListener {
        void onSubscriptionClick(SubscriptionPlan plan);
    }

    public SubscriptionAdapter(List<SubscriptionPlan> subscriptionPlans, OnSubscriptionClickListener listener) {
        this.subscriptionPlans = subscriptionPlans;
        this.listener = listener;
    }

    @NonNull
    @Override
    public SubscriptionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_subscription_plan, parent, false);
        return new SubscriptionViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SubscriptionViewHolder holder, int position) {
        SubscriptionPlan plan = subscriptionPlans.get(position);
        holder.bind(plan, listener);
    }

    @Override
    public int getItemCount() {
        return subscriptionPlans.size();
    }

    static class SubscriptionViewHolder extends RecyclerView.ViewHolder {
        private MaterialCardView cardView;
        private TextView tvPlanType;
        private TextView tvDuration;
        private TextView tvPrice;
        private TextView tvDescription;

        public SubscriptionViewHolder(@NonNull View itemView) {
            super(itemView);
            cardView = itemView.findViewById(R.id.cardSubscriptionPlan);
            tvPlanType = itemView.findViewById(R.id.tvPlanType);
            tvDuration = itemView.findViewById(R.id.tvDuration);
            tvPrice = itemView.findViewById(R.id.tvPrice);
            tvDescription = itemView.findViewById(R.id.tvDescription);
        }

        public void bind(SubscriptionPlan plan, OnSubscriptionClickListener listener) {
            tvPlanType.setText(plan.getType());
            tvDuration.setText(plan.getDurationText());
            tvPrice.setText("$" + String.format("%.2f", plan.getPrice()));
            tvDescription.setText(plan.getDescription());

            cardView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onSubscriptionClick(plan);
                }
            });
        }
    }
}