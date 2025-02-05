/*
 * Copyright 2014 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.openrtb.util;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.openrtb.OpenRtb.BidRequest;
import com.google.openrtb.OpenRtb.BidRequest.Impression;
import com.google.openrtb.OpenRtb.BidRequest.Impression.Banner;
import com.google.openrtb.OpenRtb.BidResponse;
import com.google.openrtb.OpenRtb.BidResponse.SeatBid.Bid;
import com.google.openrtb.OpenRtb.CreativeAttribute;

import com.codahale.metrics.Counter;
import com.codahale.metrics.MetricRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Validates a pair of {@link BidRequest} and its corresponding
 * {@link com.google.openrtb.OpenRtb.BidResponse.Builder}.
 * Bids with any validation problems will cause debug logs and metric updates.
 * Fatal validation errors (that would likely cause the bid to be rejected by the exchange)
 * will also be removed from the response.
 */
@Singleton
public class OpenRtbValidator {
  private static final Logger logger = LoggerFactory.getLogger(OpenRtbValidator.class);

  private final Counter unmatchedImp = new Counter();
  private final Counter invalidCreatAttr = new Counter();
  private final Counter invalidAdv = new Counter();

  @Inject
  public OpenRtbValidator(MetricRegistry metricRegistry) {
    metricRegistry.register(MetricRegistry.name(getClass(), "unmatched-imp"),
        unmatchedImp);
    metricRegistry.register(MetricRegistry.name(getClass(), "invalid-creative-attr"),
        invalidCreatAttr);
    metricRegistry.register(MetricRegistry.name(getClass(), "invalid-advertiser"),
        invalidAdv);
  }

  public boolean validate(final BidRequest request, final BidResponse.Builder response) {
    return !OpenRtbUtils.filterBids(response, new Predicate<Bid>() {
      @Override public boolean apply(Bid bid) {
        return validate(request, response, bid);
      }});
  }

  public boolean validate(BidRequest request, BidResponse.Builder response, Bid bid) {
    Impression imp = OpenRtbUtils.impWithId(request, bid.getImpid());
    if (imp == null) {
      unmatchedImp.inc();
      if (logger.isDebugEnabled()) {
        logger.debug("{} rejected, unmatched impid: {}", logId(bid), bid.getImpid());
      }
      return false;
    }
    boolean goodBid = true;

    List<String> badAdvs = check(request.getBadvList(), bid.getAdomainList());
    if (!badAdvs.isEmpty()) {
      if (logger.isDebugEnabled()) {
        logger.debug("{} rejected, blocked adomain values: {}", logId(bid), badAdvs);
      }
      invalidAdv.inc();
      goodBid = false;
    }

    if (imp.hasBanner()) {
      List<CreativeAttribute> badCreats =
          check(imp.getBanner().getBattrList(), bid.getAttrList());
      if (!badCreats.isEmpty()) {
        if (logger.isDebugEnabled()) {
          logger.debug("{} rejected, blocked attr values: {}", logId(bid), badCreats);
        }
        invalidCreatAttr.inc();
        goodBid = false;
      }
    } else if (imp.hasVideo()) {
      List<CreativeAttribute> badCreats =
          check(imp.getVideo().getBattrList(), bid.getAttrList());
      if (!badCreats.isEmpty()) {
        if (logger.isDebugEnabled()) {
          logger.debug("{} rejected, blocked attr values: {}", logId(bid), badCreats);
        }
        invalidCreatAttr.inc();
        goodBid = false;
      }

      for (Banner companion : imp.getVideo().getCompanionadList()) {
        List<CreativeAttribute> badCompCreats =
            check(companion.getBattrList(), bid.getAttrList());
        if (!badCompCreats.isEmpty()) {
          if (logger.isDebugEnabled()) {
            logger.debug("{} rejected, blocked attr values: {}", logId(bid), badCompCreats);
          }
          invalidCreatAttr.inc();
          goodBid = false;
        }
      }
    }

    return goodBid;
  }

  protected static String logId(Bid bid) {
    if (bid.hasId()) {
      return "Bid " + bid.getId();
    }
    StringBuilder sb = new StringBuilder()
        .append("Bid (impid ").append(bid.getImpid());
    if (bid.hasAdid()) {
      sb.append(", adid ").append(bid.getAdid());
    }
    return sb.append(')').toString();
  }

  protected static <T> List<T> check(List<T> reqAttrs, List<T> respAttrs) {
    List<T> bad = null;

    if (!respAttrs.isEmpty()) {
      Collection<T> reqIndex = reqAttrs.size() > 4 ? ImmutableSet.copyOf(reqAttrs) : reqAttrs;

      for (T attribute : respAttrs) {
        if (reqIndex.contains(attribute)) {
          if (bad == null) {
            bad = new ArrayList<>();
          }
          bad.add(attribute);
        }
      }
    }

    return bad == null ? ImmutableList.<T>of() : bad;
  }
}
